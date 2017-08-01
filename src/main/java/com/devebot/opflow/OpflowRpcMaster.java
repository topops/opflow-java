package com.devebot.opflow;

import com.devebot.opflow.exception.OpflowConstructorException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author drupalex
 */
public class OpflowRpcMaster {

    final Logger logger = LoggerFactory.getLogger(OpflowRpcMaster.class);
    final int PREFETCH_NUM = 1;
    
    final Lock lock = new ReentrantLock();
    final Condition idle = lock.newCondition();
    
    private final OpflowBroker broker;
    private final String responseName;
    
    public OpflowRpcMaster(Map<String, Object> params) throws OpflowConstructorException {
        Map<String, Object> brokerParams = new HashMap<String, Object>();
        brokerParams.put("mode", "rpc.master");
        brokerParams.put("uri", params.get("uri"));
        brokerParams.put("exchangeName", params.get("exchangeName"));
        brokerParams.put("exchangeType", "direct");
        brokerParams.put("routingKey", params.get("routingKey"));
        broker = new OpflowBroker(brokerParams);
        responseName = (String) params.get("responseName");
    }

    private OpflowBroker.ConsumerInfo responseConsumer;

    public final OpflowBroker.ConsumerInfo consumeResponse() {
        if (logger.isTraceEnabled()) logger.trace("invoke consumeResponse()");
        return broker.consume(new OpflowListener() {
            @Override
            public void processMessage(byte[] content, AMQP.BasicProperties properties, String queueName, Channel channel) throws IOException {
                String taskId = properties.getCorrelationId();
                if (logger.isDebugEnabled()) logger.debug("received taskId: " + taskId);
                OpflowRpcResult task = tasks.get(taskId);
                if (taskId == null || task == null) {
                    if (logger.isDebugEnabled()) logger.debug("task[" + taskId + "] not found. Skipped");
                    return;
                }
                OpflowMessage message = new OpflowMessage(content, properties.getHeaders());
                task.push(message);
                if (logger.isDebugEnabled()) logger.debug("Message has been pushed to task[" + taskId + "]");
            }
        }, OpflowUtil.buildOptions(new OpflowUtil.MapListener() {
            @Override
            public void transform(Map<String, Object> opts) {
                opts.put("queueName", responseName);
                opts.put("binding", Boolean.FALSE);
                opts.put("prefetch", PREFETCH_NUM);
                opts.put("forceNewChannel", Boolean.FALSE);
            }
        }));
    }
    
    private final Map<String, OpflowRpcResult> tasks = new HashMap<String, OpflowRpcResult>();
    
    public OpflowRpcResult request(String routineId, String content, Map<String, Object> opts) {
        return request(routineId, OpflowUtil.getBytes(content), opts);
    }
    
    public OpflowRpcResult request(String routineId, byte[] content, Map<String, Object> options) {
        Map<String, Object> opts = OpflowUtil.ensureNotNull(options);
        final boolean isStandalone = "standalone".equals((String)opts.get("mode"));
        
        final OpflowBroker.ConsumerInfo consumerInfo;
        
        if (isStandalone) {
            consumerInfo = broker.consume(new OpflowListener() {
                @Override
                public void processMessage(byte[] content, AMQP.BasicProperties properties, String queueName, Channel channel) throws IOException {
                    String taskId = properties.getCorrelationId();
                    if (logger.isDebugEnabled()) logger.debug("received taskId: " + taskId);
                    OpflowRpcResult task = tasks.get(taskId);
                    if (taskId == null || task == null) {
                        if (logger.isDebugEnabled()) logger.debug("task[" + taskId + "] not found. Skipped");
                        return;
                    }
                    OpflowMessage message = new OpflowMessage(content, properties.getHeaders());
                    task.push(message);
                    if (logger.isDebugEnabled()) logger.debug("Message has been pushed to task[" + taskId + "]");
                }
            }, OpflowUtil.buildOptions(new OpflowUtil.MapListener() {
                @Override
                public void transform(Map<String, Object> opts) {
                    opts.put("binding", Boolean.FALSE);
                    opts.put("prefetch", PREFETCH_NUM);
                }
            }));
        } else {
            if (responseConsumer == null) {
                responseConsumer = consumeResponse();
            }
            consumerInfo = responseConsumer;
        }
        
        final String taskId = UUID.randomUUID().toString();
        OpflowTask.Listener listener = new OpflowTask.Listener() {
            @Override
            public void handleEvent() {
                tasks.remove(taskId);
                if (isStandalone) {
                    cancelConsumer(consumerInfo);
                }
                if (tasks.isEmpty()) {
                    lock.lock();
                    try {
                        idle.signal();
                    } finally {
                        lock.unlock();
                    }
                }
                if (logger.isDebugEnabled()) logger.debug("tasks.size(): " + tasks.size());
            }
        };
        
        if (routineId != null) {
            opts.put("routineId", routineId);
        }
        
        OpflowRpcResult task = new OpflowRpcResult(opts, listener);
        tasks.put(taskId, task);
        
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("requestId", task.getRequestId());
        headers.put("routineId", task.getRoutineId());
        
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties
                .Builder()
                .headers(headers)
                .correlationId(taskId);
        
        if (isStandalone) {
            builder.replyTo(consumerInfo.getQueueName());
        }
        
        AMQP.BasicProperties props = builder.build();

        broker.produce(content, props, null);
        
        return task;
    }

    public void close() {
        lock.lock();
        try {
            while(tasks.size() > 0) idle.await();
            if (responseConsumer != null) cancelConsumer(responseConsumer);
            if (broker != null) broker.close();
        } catch(Exception ex) {
        } finally {
            lock.unlock();
        }
    }
    
    private void cancelConsumer(OpflowBroker.ConsumerInfo consumerInfo) {
        try {
            consumerInfo.getChannel().basicCancel(consumerInfo.getConsumerTag());
            if (logger.isDebugEnabled()) {
                logger.debug("Queue[" + consumerInfo.getQueueName() + "]/ConsumerTag[" + consumerInfo.getConsumerTag() + "] is cancelled");
            }
        } catch (IOException ex) {
            if (logger.isDebugEnabled()) logger.debug("cancel consumer failed, IOException: " + ex.getMessage());
        }
    }
}