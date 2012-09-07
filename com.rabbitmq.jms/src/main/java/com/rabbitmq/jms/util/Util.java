package com.rabbitmq.jms.util;

import java.io.IOException;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.util.UUID;

import javax.jms.IllegalStateException;
import javax.jms.InvalidClientIDException;
import javax.jms.InvalidDestinationException;
import javax.jms.JMSException;
import javax.jms.JMSSecurityException;
import javax.jms.MessageEOFException;
import javax.jms.MessageFormatException;
import javax.jms.MessageNotReadableException;
import javax.jms.MessageNotWriteableException;

public class Util {
    private static final Util util = new Util();

    /**
     * Returns the singleton of this class
     * @return
     */
    public static Util util() {
        return util;
    }

    /**
     * Wraps an exception and re throws a {@link JMSException}
     * This method will always throw a {@link JMSException}
     * and is used to encapsulate Rabbit API exceptions (all declared as
     * {@link IOException}
     * @param x the exceptio to wrap
     * @param message the message for the {@link JMSException#JMSException(String)} constructor. If null {@link Exception#getMessage()} will be used
     * @return does not return anything, always throws a {@link JMSException}
     * @throws JMSException always on every invocation
     */
    public JMSException handleException(Exception x, String message) throws JMSException {
        JMSException jx = new JMSException(message == null ? x.getMessage() : message);
        jx.initCause(x);
        throw jx;
    }

    /**
     * @see {@link #handleException(Exception, String)}
     * @param x
     * @return
     * @throws JMSException
     */
    public JMSException handleException(Exception x) throws JMSException {
        return this.handleException(x, x.getMessage());
    }

    public JMSSecurityException handleSecurityException(Exception x) throws JMSException {
        JMSSecurityException jx = new JMSSecurityException(x.getMessage());
        jx.initCause(x);
        throw jx;
    }
    
    public JMSSecurityException handleMessageFormatException(Exception x) throws JMSException {
        MessageFormatException jx = new MessageFormatException(x.getMessage());
        jx.initCause(x);
        throw jx;
    }

    /**
     * Throws the supplied exception if the bool parameter is true
     * @param bool throws the supplied if this parameter is set to true
     * @param msg
     * @param clazz the type of exception that should be thrown
     * @throws JMSException
     */
    public JMSException checkTrue(boolean bool, String msg, Class<? extends JMSException> clazz) throws JMSException {
        if (bool) {
            if (IllegalStateException.class.equals(clazz)) {
                throw new IllegalStateException(msg);
            } else if (InvalidClientIDException.class.equals(clazz)) {
                throw new InvalidClientIDException(msg);
            } else if (IllegalStateException.class.equals(clazz)) {
                throw new IllegalStateException(msg);
            } else if (InvalidDestinationException.class.equals(clazz)) {
                throw new InvalidDestinationException(msg);
            } else if (JMSException.class.equals(clazz)) {
                throw new JMSException(msg);
            } else if (JMSSecurityException.class.equals(clazz)) {
                throw new JMSSecurityException(msg);
            } else if (MessageEOFException.class.equals(clazz)) {
                throw new MessageEOFException(msg);
            } else if (MessageFormatException.class.equals(clazz)) {
                throw new MessageFormatException(msg);
            } else if (MessageNotReadableException.class.equals(clazz)) {
                throw new MessageNotReadableException(msg);
            } else if (MessageNotWriteableException.class.equals(clazz)) {
                throw new MessageNotWriteableException(msg);
            } else {
                throw new JMSException(msg);
            }
        } else {
            return null;
        }
            
    }
    
    /**
     * Generates a random UUID string
     * @return a random UUID string
     */
    public String generateUUIDTag() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Utility method to write an object as a primitive or as an object
     * @param s the object to write
     * @param out the stream to write it to
     * @param allowSerializable true if we allow objects other than serializable
     * @throws IOException
     * @throws NullPointerException if s is null
     */
    public void writePrimitiveData(Object s, ObjectOutput out, boolean allowSerializable) throws IOException, MessageFormatException {
        if(s==null) {
            throw new NullPointerException();
        } else if (s instanceof Boolean) {
            out.writeBoolean(((Boolean) s).booleanValue());
        } else if (s instanceof Byte) {
            out.writeByte(((Byte) s).byteValue());
        } else if (s instanceof Short) {
            out.writeShort((((Short) s).shortValue()));
        } else if (s instanceof Integer) {
            out.writeInt(((Integer) s).intValue());
        } else if (s instanceof Long) {
            out.writeLong(((Long) s).longValue());
        } else if (s instanceof Float) {
            out.writeFloat(((Float) s).floatValue());
        } else if (s instanceof Double) {
            out.writeDouble(((Double) s).doubleValue());
        } else if (s instanceof String) {
            out.writeUTF((String) s);
        } else if (s instanceof Character) {
            out.writeChar(((Character) s).charValue());
        } else if (s instanceof Character) {
            out.writeChar(((Character) s).charValue());
        } else if (allowSerializable && s instanceof Serializable) {
            out.writeObject(s);
        } else if (s instanceof byte[]) {
            out.write((byte[])s);
        } else
            throw new MessageFormatException(s + " is not a recognized primitive type.");
        

    }
}
