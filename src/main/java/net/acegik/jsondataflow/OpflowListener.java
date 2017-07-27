package net.acegik.jsondataflow;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.io.IOException;

public interface OpflowListener {
    public void processMessage(byte[] content, AMQP.BasicProperties properties, String queueName, Channel channel) throws IOException;
}