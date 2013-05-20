/* Copyright © 2013 VMware, Inc. All rights reserved. */
package com.rabbitmq.jms.client;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.jms.IllegalStateException;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.QueueReceiver;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicSubscriber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.jms.admin.RMQDestination;
import com.rabbitmq.jms.util.AbortableHolder;
import com.rabbitmq.jms.util.AbortedException;
import com.rabbitmq.jms.util.EntryExitManager;
import com.rabbitmq.jms.util.RMQJMSException;
import com.rabbitmq.jms.util.TimeTracker;
import com.rabbitmq.jms.util.Util;

/**
 * The implementation of {@link MessageConsumer} in the RabbitMQ JMS Client.
 * <p>
 * Single message {@link #receive receive()}s are implemented by abortable polling in {@link DelayedReceiver}.
 * </p>
 * <p>
 * {@link MessageListener#onMessage} calls are implemented with a more conventional {@link Consumer}.
 * </p>
 */
public class RMQMessageConsumer implements MessageConsumer, QueueReceiver, TopicSubscriber {
    private final Logger logger = LoggerFactory.getLogger(RMQMessageConsumer.class);

    private static final int DEFAULT_BATCHING_SIZE = 5;
    private static final long STOP_TIMEOUT_MS = 1000; // ONE SECOND
    /** The destination that this consumer belongs to */
    private final RMQDestination destination;
    /** The session that this consumer was created under */
    private final RMQSession session;
    /** Unique tag, used when creating AMQP queues for a consumer that thinks it's a topic */
    private final String uuidTag;
    /** The selector used to filter messages consumed */
    private final String messageSelector;
    /** The {@link Consumer} that we use to subscribe to Rabbit messages which drives {@link MessageListener#onMessage}. */
    private final AtomicReference<MessageListenerConsumer> listenerConsumer = new AtomicReference<MessageListenerConsumer>();
    /** Entry and exit of application threads calling {@link #receive} are managed by an {@link EntryExitManager}. */
    private final EntryExitManager receiveManager = new EntryExitManager();
    /** We track things that need to be aborted (for a Connection.close()). Typically these are waits. */
    private final AbortableHolder abortables = new AbortableHolder();
    /** Is this consumer closed? This value can change to true, but never changes back. */
    private volatile boolean closed = false;
    /** If this consumer is in the process of closing. */
    private volatile boolean closing = false;
    /** {@link MessageListener}, set by the user. */
    private volatile MessageListener messageListener;
    /** Flag to check if we are a durable subscription. */
    private volatile boolean durable = false;
    /** Flag to check if we have noLocal set */
    private volatile boolean noLocal = false;
    /** For getting messages from {@link #receive} queues. */
    private final DelayedReceiver delayedReceiver;
    /** Record and preserve the need to acknowledge automatically */
    private final boolean autoAck;

    /** Track how this consumer if being used. */
    private final AtomicInteger numberOfReceives = new AtomicInteger(0);

    /**
     * Creates a RMQMessageConsumer object. Internal constructor used by {@link RMQSession}
     *
     * @param session - the session object that created this consume
     * @param destination - the destination for this consumer
     * @param uuidTag - when creating queues to a topic, we need a unique queue name for each consumer. This is the
     *            unique name.
     * @param paused - true if the connection is {@link javax.jms.Connection#stop}ped, false otherwise.
     */
    RMQMessageConsumer(RMQSession session, RMQDestination destination, String uuidTag, boolean paused, String messageSelector) {
        this.session = session;
        this.destination = destination;
        this.uuidTag = uuidTag;
        this.delayedReceiver = new DelayedReceiver(DEFAULT_BATCHING_SIZE, this);
        this.messageSelector = messageSelector;
        if (!paused)
            this.receiveManager.openGate();
        this.autoAck = determineAutoAck(session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Queue getQueue() throws JMSException {
        return this.destination;
    }

    /**
     * {@inheritDoc} Note: This implementation always returns null
     */
    @Override
    public String getMessageSelector() throws JMSException {
        return this.messageSelector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageListener getMessageListener() throws JMSException {
        return this.messageListener;
    }

    /**
     * @return whether this Consumer is being used asynchronously
     */
    boolean messageListenerIsSet() {
        return (null != this.messageListener);
    }

    /**
     * Dispose of any Rabbit Consumer that may be active and tracked.
     */
    private void removeListenerConsumer() {
        MessageListenerConsumer listConsumer = this.listenerConsumer.getAndSet(null);
        if (listConsumer != null) {
            this.abortables.remove(listConsumer);
            listConsumer.stop();  // orderly stop
        }
    }

    /**
     * From the on-line JavaDoc: <blockquote>
     * <p>
     * Sets the message consumer's {@link MessageListener}.
     * </p>
     * <p>
     * Setting the message listener to <code>null</code> is the equivalent of clearing the message listener for the
     * message consumer.
     * </p>
     * <p>
     * The effect of calling {@link #setMessageListener} while messages are being consumed by an existing listener or
     * the consumer is being used to consume messages synchronously is undefined.
     * </p>
     * </blockquote>
     * <p>
     * Notwithstanding, we attempt to clear the previous listener gracefully (by cancelling the Consumer) if there is
     * one.
     * </p>
     * {@inheritDoc}
     */
    @Override
    public void setMessageListener(MessageListener messageListener) throws JMSException {
        if (messageListener == this.messageListener) { // no change, so do nothing
            logger.info("MessageListener({}) already set", messageListener);
            return;
        }
        if (!this.session.aSyncAllowed()) {
            throw new IllegalStateException("A MessageListener cannot be set if receive() is outstanding on a session. (See JMS 1.1 §4.4.6.)");
        }
        logger.trace("setting MessageListener({})", messageListener);
        this.removeListenerConsumer();  // if there is any
        this.messageListener = messageListener;
        this.setNewListenerConsumer(messageListener); // if needed
    }

    /**
     * Create a new RabitMQ Consumer, if necessary.
     * @param messageListener to drive from Consumer; no Consumer is created if this is null.
     * @throws IllegalStateException
     */
    private void setNewListenerConsumer(MessageListener messageListener) throws IllegalStateException {
        if (messageListener != null) {
            MessageListenerConsumer mlConsumer =
                                                 new MessageListenerConsumer(
                                                                             this,
                                                                             getSession().getChannel(),
                                                                             messageListener,
                                                                             TimeUnit.MILLISECONDS.toNanos(this.session.getConnection()
                                                                                                                       .getTerminationTimeout()));
            if (this.listenerConsumer.compareAndSet(null, mlConsumer)) {
                this.abortables.add(mlConsumer);
                if (!this.getSession().getConnection().isStopped()) {
                    mlConsumer.start();
                }
            } else {
                mlConsumer.abort();
                throw new IllegalStateException("MessageListener concurrently set on Consumer " + this);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Message receive() throws JMSException {
        if (this.closed || this.closing)
            throw new IllegalStateException("Consumer is closed or closing.");
        logger.trace("receive (wait forever)");
        return receive(new TimeTracker());
    }

    /**
     * Receive a single message from the destination, waiting for up to <code>timeout</code> milliseconds if necessary.
     * <p>
     * The JMS 1.1 specification for {@link javax.jms.Connection#stop()} says:
     * </p>
     * <blockquote>
     * <p>
     * When the connection is stopped, delivery to all the connection's message consumers is inhibited: synchronous
     * receives block, and messages are not delivered to message listeners. {@link javax.jms.Connection#stop()} blocks until
     * receives and/or message listeners in progress have completed.
     * </p>
     * </blockquote>
     * <p>
     * For synchronous gets, we potentially have to block on the way in.
     * <p/>
     * {@inheritDoc}
     *
     * @param timeout - (in milliseconds) zero means wait forever; {@inheritDoc}
     */
    @Override
    public Message receive(long timeout) throws JMSException {
        if (this.closed || this.closing)
            throw new IllegalStateException("Consumer is closed or closing.");
        logger.trace("receive(timeout={}ms)", timeout);
        return receive(timeout==0 ? new TimeTracker() : new TimeTracker(timeout, TimeUnit.MILLISECONDS));
    }

    /**
     * Returns true if messages should be automatically acknowledged upon arrival
     *
     * @return true if {@link Session#getAcknowledgeMode()}=={@link Session#DUPS_OK_ACKNOWLEDGE} or
     *         {@link Session#getAcknowledgeMode()}=={@link Session#AUTO_ACKNOWLEDGE} or transacted.
     */
    boolean isAutoAck() {
        return this.autoAck;
    }

    private static boolean determineAutoAck(RMQSession session) {
        int ackMode = session.getAcknowledgeModeNoException();
        return (ackMode != Session.CLIENT_ACKNOWLEDGE);  // could be transacted or auto_ack
    }

    /**
     * Register a {@link Consumer} with the Rabbit API to receive messages
     *
     * @param consumer the SynchronousConsumer being registered
     * @param consTag the ConsumerTag to use for RabbitMQ callbacks
     * @throws IOException from RabbitMQ calls
     * @see Channel#basicConsume(String, boolean, String, boolean, boolean, java.util.Map, Consumer)
     */
    void basicConsume(Consumer consumer, String consTag) throws IOException {
        String name = rmqQueueName();
        // never ack async messages automatically, only when we can deliver them
        // to the actual consumer so we pass in false as the auto ack mode
        // we must support setMessageListener(null) while messages are arriving
        // and those message we NACK
        logger.debug("consuming from queue '{}' with tag '{}'", name, consTag);
        getSession().getChannel()
         .basicConsume(name, /* the name of the queue */
                       false, /* autoack is ALWAYS false, otherwise we risk acking messages that are received
                               * to the client but the client listener(onMessage) has not yet been invoked */
                       consTag, /* the consumer tag to use */
                       this.noLocal, /* RabbitMQ supports the noLocal flag for subscriptions */
                       false, /* exclusive will always be false: exclusive consumer access true means only this
                               * consumer can access the queue. */
                       null, /* there are no custom arguments for the subscription */
                       consumer /* the callback object for handleDelivery(), etc. */
                       );
    }

    /**
     * RabbitMQ {@link Channel#basicConsume} should accept a {@link null} consumer-tag, to cause it to generate a new,
     * unique one for us; but it doesn't :-(
     */
    static final String newConsumerTag() {
        return Util.generateUUID("jms-consumer-");
        // return null;
    }

    String rmqQueueName() {
        if (this.destination.isQueue()) {
            /* javax.jms.Queue we share a single AMQP queue among all consumers hence the name will the the name of the
             * destination */
            return this.destination.getName();
        } else {
            /* javax.jms.Topic we created a unique AMQP queue for each consumer and that name is unique for this
             * consumer alone */
            return this.getUUIDTag();
        }
    }

    int getNumberOfReceives() {
        return this.numberOfReceives.get();
    }

    private RMQMessage receive(TimeTracker tt)  throws JMSException {
    /* Pseudocode:
     *  get (synchronous read)
     *  if something return it;
     *  if nothing, then poll (intervals up to time limit given)
     *  if still nothing return nothing.
     */
        if (!this.session.syncAllowed()) {
            throw new IllegalStateException("A session may not receive() when a MessageListener is set. (See JMS 1.1 §4.4.6.)");
        }
        this.numberOfReceives.incrementAndGet();
        try {
            if (!this.receiveManager.enter(tt))  // stopped?
                return null; // timed out while stopped
            /* Try to receive a message, there's some time left! */
            try {
                GetResponse resp = this.delayedReceiver.get(tt);
                if (resp == null) return null; // nothing received in time or aborted
                if (this.autoAck) this.session.explicitAck(resp.getEnvelope().getDeliveryTag());
                return this.processMessage(resp, this.autoAck);
            } finally {
                this.receiveManager.exit();
            }
        } catch (AbortedException _) {
            /* If we were aborted (closed) we return null, too. */
            return null;
        } catch (InterruptedException _) {
            /* Someone interrupted us -- we ought to terminate */
            Thread.currentThread().interrupt(); // reset interrupt status
            return null;
        } finally {
            this.numberOfReceives.decrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Message receiveNoWait() throws JMSException {
        logger.trace("receive without waiting");
        if (this.closed || this.closing)
            throw new IllegalStateException("Consumer is closed or closing.");
        return receive(TimeTracker.ZERO);
    }

    /**
     * Converts a {@link GetResponse} to a {@link Message}
     *
     * @param response - the message information from RabbitMQ {@link Channel#basicGet} or via a {@link Consumer}.
     * @param acknowledged - whether the message has been acknowledged.
     * @return the JMS message corresponding to the RabbitMQ message
     * @throws JMSException
     */
    RMQMessage processMessage(GetResponse response, boolean acknowledged) throws JMSException {
        if (response == null) /* return null if the response is null */
            return null;

        try {
            /* Deserialize the message from the byte[] */
            RMQMessage message = RMQMessage.fromMessage(response.getBody());
            /* Received messages contain a reference to their delivery tag this is used in Message.acknowledge */
            message.setRabbitDeliveryTag(response.getEnvelope().getDeliveryTag());
            /* Set a reference to this session this is used in Message.acknowledge */
            message.setSession(getSession());
            /* Set the destination this message was received from */
            message.setJMSDestination(getDestination());
            /* Initially the message is readOnly properties == true until clearProperties has been called readOnly body
             * == true until clearBody has been called */
            message.setReadonly(true);
            /* Set the redelivered flag. we inherit this from the RabbitMQ broker */
            message.setJMSRedelivered(response.getEnvelope().isRedeliver());
            if (!acknowledged) {
                /* If the message has not been acknowledged automatically let the session know so that it can track
                 * unacknowledged messages */
                getSession().unackedMessageReceived(message);
            }
            return message;
        } catch (IOException x) {
            throw new RMQJMSException(x);
        } catch (ClassNotFoundException x) {
            throw new RMQJMSException(x);
        } catch (IllegalAccessException x) {
            throw new RMQJMSException(x);
        } catch (InstantiationException x) {
            throw new RMQJMSException(x);
        }
    }

    /**
     * JMS Spec:
     * <blockquote>
     * <p>Closes the message consumer. Since a provider may allocate some resources on behalf of a
     * MessageConsumer outside the Java virtual machine, clients should close them when they are not needed. Relying on
     * garbage collection to eventually reclaim these resources may not be timely enough. This call blocks until a
     * receive or message listener in progress has completed.</p>
     * <p>A blocked message consumer receive call returns null when
     * this message consumer is closed.</p>
     * </blockquote>
     * {@inheritDoc}
     */
    @Override
    public void close() throws JMSException {
        this.getSession().consumerClose(this);
    }

    /**
     * @return <code>true</code> if {@link #close()} has been invoked and the call has completed, or <code>false</code>
     *         if {@link #close()} has not been called or is in progress
     */
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * Method called when message consumer is closed
     */
    void internalClose() throws JMSException {
        logger.trace("close consumer({})", this);
        this.closing = true;
        /* If we are stopped, we must break that. This will release all threads waiting on the gate and effectively
         * disable the use of the gate */
        this.receiveManager.closeGate(); // stop any more entering receive region
        this.receiveManager.abortWaiters(); // abort any that arrive now

        this.delayedReceiver.close(); // close the synchronous receive, if any

        /* stop and remove any active subscription - waits for onMessage processing to finish */
        this.removeListenerConsumer();

        this.abortables.abort(); // abort Consumers of both types that remain

        this.closed = true;
        this.closing = false;
    }

    /**
     * Returns the destination this message consumer is registered with
     *
     * @return the destination this message consumer is registered with
     */
    public RMQDestination getDestination() {
        return this.destination;
    }

    /**
     * Returns the session this consumer was created by
     *
     * @return the session this consumer was created by
     */
    public RMQSession getSession() {
        return this.session;
    }

    /**
     * The unique tag that this consumer holds
     *
     * @return unique tag that this consumer holds
     */
    public String getUUIDTag() {
        return this.uuidTag;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Topic getTopic() throws JMSException {
        if (this.getDestination().isQueue()) {
            throw new JMSException("Destination is of type Queue, not Topic");
        }
        return this.getDestination();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getNoLocal() throws JMSException {
        return getNoLocalNoException();
    }

    /**
     * @see #getNoLocal()
     * @return true if the noLocal variable was set
     */
    public boolean getNoLocalNoException() {
        return this.noLocal;
    }

    /**
     * Stops this consumer from receiving messages. This is called by the session indirectly when
     * {@link javax.jms.Connection#stop()} is invoked. In this implementation, any async consumers will be cancelled,
     * only to be re-subscribed when <code>resume()</code>d.
     *
     * @throws InterruptedException if the thread is interrupted
     */
    public void pause() throws InterruptedException {
        this.receiveManager.closeGate();
        this.receiveManager.waitToClear(new TimeTracker(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        this.abortables.stop();
    }

    /**
     * Resubscribes all async listeners and continues to receive messages
     *
     * @see javax.jms.Connection#start()
     * @throws javax.jms.JMSException if the thread is interrupted
     */
    void resume() throws JMSException {
        this.abortables.start(); // async listener restarted
        this.receiveManager.openGate(); // sync listener allowed to run
    }

    /**
     * @return true if durable
     */
    public boolean isDurable() {
        return this.durable;
    }

    /**
     * Set durable status
     *
     * @param durable
     */
    void setDurable(boolean durable) {
        this.durable = durable;
    }

    /**
     * Configures the no local for this consumer. This is currently only used when subscribing an async consumer.
     *
     * @param noLocal - if <code>true</code>, and the destination is a {@link Topic}, inhibits the delivery of messages published by its own
     *            connection. The behaviour for <code>noLocal</code> is not specified if the destination is a {@link Queue}.
     */
    void setNoLocal(boolean noLocal) {
        this.noLocal = noLocal;
    }

    GetResponse getFromRabbitQueue() {
        String qN = rmqQueueName();
        try {
            return getSession().getChannel().basicGet(qN, false);
        } catch (IOException e) {
            logger.error("basicGet for queue '{}' threw exception", qN, e);
            // TODO: mark consumer broken, with error reason
            return null;
        }
    }

    void explicitNack(long deliveryTag) {
        this.session.explicitNack(deliveryTag);
    }

    void explicitAck(long deliveryTag) {
        this.session.explicitAck(deliveryTag);
    }
}