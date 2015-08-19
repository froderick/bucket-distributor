package org.funtastic.bucket;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.funtastic.dist.rabbit.RabbitDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Test {

    public static void main(String[] args) throws Exception {

        ConnectionFactory cf = new ConnectionFactory();
        cf.setAutomaticRecoveryEnabled(true);
        cf.setRequestedHeartbeat(5);

        Set<String> buckets = new HashSet();
        for (int i=0; i<100; i++) {
            buckets.add("" + i);
        }

        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        final Connection c = cf.newConnection();

        final RabbitDistributor dist = new RabbitDistributor();
        dist.setConnection(c);
        dist.setName("test");
        dist.setDefaultBuckets(buckets);
        dist.setScheduler(scheduler);

        dist.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    dist.stop();
                    scheduler.shutdownNow();
                    c.close();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        while (true) {
            try {
                Set<String> l = dist.buckets();
                for (String b : l) {
                    Set<String> set = new HashSet();
                    set.add(b);
                    dist.release(set);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            Thread.sleep(1000L);
        }
    }
}
