package org.funtastic.bucket;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BucketExample {

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

        final RabbitBucketDistributor s = new RabbitBucketDistributor(c, "test", buckets, scheduler,
            1, TimeUnit.MINUTES, // announce period
            2, TimeUnit.MINUTES, // expiration period
            5, 5, TimeUnit.SECONDS // partition update delay/period
            );

        s.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    s.stop();
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
                Set<String> l = s.buckets();
                for (String b : l) {
                    Set<String> set = new HashSet();
                    set.add(b);
                    s.release(set);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            Thread.sleep(1000L);
        }
    }
}
