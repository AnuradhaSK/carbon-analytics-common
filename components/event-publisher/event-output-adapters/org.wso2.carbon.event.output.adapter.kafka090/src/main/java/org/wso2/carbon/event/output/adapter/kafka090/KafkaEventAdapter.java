/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.event.output.adapter.kafka090;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.event.output.adapter.core.EventAdapterUtil;
import org.wso2.carbon.event.output.adapter.core.OutputEventAdapter;
import org.wso2.carbon.event.output.adapter.core.OutputEventAdapterConfiguration;
import org.wso2.carbon.event.output.adapter.core.exception.OutputEventAdapterException;
import org.wso2.carbon.event.output.adapter.core.exception.TestConnectionNotSupportedException;
import org.wso2.carbon.event.output.adapter.kafka090.internal.util.KafkaEventAdapterConstants;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class KafkaEventAdapter implements OutputEventAdapter {

    private static final Log log = LogFactory.getLog(KafkaEventAdapter.class);
    private static ThreadPoolExecutor threadPoolExecutor;
    private OutputEventAdapterConfiguration eventAdapterConfiguration;
    private Map<String, String> globalProperties;
    private org.apache.kafka.clients.producer.Producer<String, Object> producer;
    private int tenantId;

    public KafkaEventAdapter(OutputEventAdapterConfiguration eventAdapterConfiguration,
                             Map<String, String> globalProperties) {
        this.eventAdapterConfiguration = eventAdapterConfiguration;
        this.globalProperties = globalProperties;
    }

    @Override
    public void init() throws OutputEventAdapterException {

        tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();

        //ThreadPoolExecutor will be assigned  if it is null
        if (threadPoolExecutor == null) {
            int minThread;
            int maxThread;
            int jobQueSize;
            long defaultKeepAliveTime;

            //If global properties are available those will be assigned else constant values will be assigned
            if (globalProperties.get(KafkaEventAdapterConstants.ADAPTER_MIN_THREAD_POOL_SIZE_NAME) != null) {
                minThread = Integer.parseInt(globalProperties.get(KafkaEventAdapterConstants.ADAPTER_MIN_THREAD_POOL_SIZE_NAME));
            } else {
                minThread = KafkaEventAdapterConstants.ADAPTER_MIN_THREAD_POOL_SIZE;
            }

            if (globalProperties.get(KafkaEventAdapterConstants.ADAPTER_MAX_THREAD_POOL_SIZE_NAME) != null) {
                maxThread = Integer.parseInt(globalProperties.get(KafkaEventAdapterConstants.ADAPTER_MAX_THREAD_POOL_SIZE_NAME));
            } else {
                maxThread = KafkaEventAdapterConstants.ADAPTER_MAX_THREAD_POOL_SIZE;
            }

            if (globalProperties.get(KafkaEventAdapterConstants.ADAPTER_KEEP_ALIVE_TIME_NAME) != null) {
                defaultKeepAliveTime = Integer.parseInt(globalProperties.get(
                        KafkaEventAdapterConstants.ADAPTER_KEEP_ALIVE_TIME_NAME));
            } else {
                defaultKeepAliveTime = KafkaEventAdapterConstants.DEFAULT_KEEP_ALIVE_TIME_IN_MILLIS;
            }

            if (globalProperties.get(KafkaEventAdapterConstants.ADAPTER_EXECUTOR_JOB_QUEUE_SIZE_NAME) != null) {
                jobQueSize = Integer.parseInt(globalProperties.get(
                        KafkaEventAdapterConstants.ADAPTER_EXECUTOR_JOB_QUEUE_SIZE_NAME));
            } else {
                jobQueSize = KafkaEventAdapterConstants.ADAPTER_EXECUTOR_JOB_QUEUE_SIZE;
            }

            threadPoolExecutor = new ThreadPoolExecutor(minThread, maxThread, defaultKeepAliveTime,
                    TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(jobQueSize), new ThreadFactoryBuilder()
                    .setNameFormat("KafkaOutput090-" + eventAdapterConfiguration.getName() + "-executor-thread-%d").build());
        }

    }

    @Override
    public void testConnect() throws TestConnectionNotSupportedException {
        throw new TestConnectionNotSupportedException("Test connection is not available");
    }

    @Override
    public void connect() {
        Map<String, String> staticProperties = eventAdapterConfiguration.getStaticProperties();

        String kafkaConnect = staticProperties.get(KafkaEventAdapterConstants.ADAPTOR_META_BROKER_LIST);
        String optionalConfigs = staticProperties.get(KafkaEventAdapterConstants.ADAPTOR_OPTIONAL_CONFIGURATION_PROPERTIES);

        Properties props = new Properties();
        props.put("bootstrap.servers", kafkaConnect);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

        readOptionalConfigs(props, optionalConfigs);

        producer = new KafkaProducer<String,Object>(props);
        log.info("Kafka producer created.");

    }

    public static void readOptionalConfigs(Properties props, String optionalConfigs) {
        if (optionalConfigs != null && !optionalConfigs.isEmpty()) {
            String[] optionalProperties = optionalConfigs.split(KafkaEventAdapterConstants.HEADER_SEPARATOR);
            if (optionalProperties.length > 0) {
                for (String header : optionalProperties) {
                    try {
                        String[] configPropertyWithValue = header.split(KafkaEventAdapterConstants.ENTRY_SEPARATOR, 2);
                        props.put(configPropertyWithValue[0], configPropertyWithValue[1]);
                    } catch (Exception e) {
                        log.warn("Optional property '" + header + "' is not defined in the correct format.", e);
                    }
                }
            }
        }
    }

    @Override
    public void publish(Object message, Map<String, String> dynamicProperties) {

        //By default auto.create.topics.enable is true, then no need to create topic explicitly
        String topic = dynamicProperties.get(KafkaEventAdapterConstants.ADAPTOR_PUBLISH_TOPIC);
        try {
            threadPoolExecutor.submit(new KafkaSender(topic, message));
        } catch (RejectedExecutionException e) {
            EventAdapterUtil.logAndDrop(eventAdapterConfiguration.getName(), message, "Job queue is full", e, log, tenantId);
        }
    }

    @Override
    public void disconnect() {
        //close producer
        if (producer != null) {
            producer.close();
        }
    }

    @Override
    public void destroy() {
        //not required
    }

    @Override
    public boolean isPolled() {
        return false;
    }


    class KafkaSender implements Runnable {

        String topic;
        Object message;

        KafkaSender(String topic, Object message) {
            this.topic = topic;
            this.message = message;
        }

        @Override
        public void run() {
            try {
                producer.send(new ProducerRecord<String,Object>(topic, message));
            } catch (Throwable e) {
                log.error("Unexpected error when sending event via Kafka Output Adatper 090:" + e.getMessage(), e);
            }
        }
    }

}
