package com.freedom.messagebus.client.handler.common;

import com.freedom.messagebus.client.IChannelDestroyer;
import com.freedom.messagebus.client.MessageContext;
import com.freedom.messagebus.client.handler.IHandlerChain;
import com.freedom.messagebus.client.message.model.Message;
import com.freedom.messagebus.client.message.model.MessageFactory;
import com.freedom.messagebus.client.message.model.MessageType;
import com.freedom.messagebus.client.message.transfer.IMessageBodyTransfer;
import com.freedom.messagebus.client.message.transfer.MessageBodyTransferFactory;
import com.freedom.messagebus.client.message.transfer.MessageHeaderTransfer;
import com.freedom.messagebus.common.ExceptionHelper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.QueueingConsumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * the receive event loop
 */
public class ReceiveEventLoop implements Runnable {

    private static final Log logger = LogFactory.getLog(ReceiveEventLoop.class);

    private QueueingConsumer  currentConsumer;
    private IChannelDestroyer channelDestroyer;
    private MessageContext    context;
    private IHandlerChain     chain;
    private Thread            currentThread;
    private boolean           isClosed;

    public ReceiveEventLoop() {
        this.currentThread = new Thread(this);
        this.currentThread.setDaemon(true);
    }

    @Override
    public void run() {
        try {
            while (true) {
                QueueingConsumer.Delivery delivery = this.currentConsumer.nextDelivery();

                AMQP.BasicProperties properties = delivery.getProperties();
                byte[] msgBody = delivery.getBody();

                context.getChannel().basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                String msgTypeStr = properties.getType();
                if (msgTypeStr == null || msgTypeStr.isEmpty()) {
                    logger.error("[run] message type is null or empty");
                    continue;
                }

                MessageType msgType = null;
                try {
                    msgType = MessageType.lookup(msgTypeStr);
                } catch (UnknownError unknownError) {
                    throw new RuntimeException("unknown message type :" + msgTypeStr);
                }
                Message msg = MessageFactory.createMessage(msgType);
                initMessage(msg, msgType, properties, msgBody);

                this.context.setConsumedMsg(msg);
                //launch post pipeline
                this.chain.startPost();
                this.chain.handle(this.context);
            }
        } catch (InterruptedException e) {
            logger.info("[run] close the consumer's message handler!");
        } catch (Exception e) {
            ExceptionHelper.logException(logger, e, "run");
            this.shutdown();
        }

        logger.info("******** thread id " + this.getThreadID() + " quit from message receiver ********");
    }

    public void startEventLoop() {
        this.isClosed = false;
        this.currentThread.start();
    }

    /**
     * shut down launch a interrupt to itself
     */
    public void shutdown() {
        if (!this.isClosed) {
            this.channelDestroyer.destroy(context.getChannel());
            this.currentThread.interrupt();
            this.isClosed = true;
        }
    }

    public boolean isAlive() {
        return this.currentThread.isAlive() && !this.isClosed;
    }

    protected long getThreadID() {
        return this.currentThread.getId();
    }

    private void initMessage(Message msg, MessageType msgType, AMQP.BasicProperties properties, byte[] bodyData) {
        MessageHeaderTransfer.unbox(properties, msgType, msg.getMessageHeader());

        IMessageBodyTransfer msgBodyProcessor = MessageBodyTransferFactory.createMsgBodyProcessor(msgType);
        msg.setMessageBody(msgBodyProcessor.unbox(bodyData));
    }


    public QueueingConsumer getCurrentConsumer() {
        return currentConsumer;
    }

    public void setCurrentConsumer(QueueingConsumer currentConsumer) {
        this.currentConsumer = currentConsumer;
    }


    public IChannelDestroyer getChannelDestroyer() {
        return channelDestroyer;
    }

    public void setChannelDestroyer(IChannelDestroyer channelDestroyer) {
        this.channelDestroyer = channelDestroyer;
    }


    public MessageContext getContext() {
        return context;
    }

    public void setContext(MessageContext context) {
        this.context = context;
    }

    public IHandlerChain getChain() {
        return chain;
    }

    public void setChain(IHandlerChain chain) {
        this.chain = chain;
    }

}
