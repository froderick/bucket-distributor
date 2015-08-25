package org.funtastic.hyrax;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import hyrax.dist.RabbitDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Example {

    public static void main(String[] args) throws Exception {

        // timing clojure runtime startup overhead (normally about 900ms)
        long start = System.currentTimeMillis();
        IFn fn = Clojure.var("clojure", "slurp");
        long end = System.currentTimeMillis();
        System.out.println(end - start);

        ConnectionFactory cf = new ConnectionFactory();
        cf.setAutomaticRecoveryEnabled(true);
        cf.setRequestedHeartbeat(5);

        Set<String> buckets = new HashSet();
        for (int i=0; i<100; i++) {
            buckets.add("" + i);
        }

        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        final Connection c = cf.newConnection();

        final RabbitDistributor rd = new RabbitDistributor();
        rd.setConnection(c);
        rd.setName("test");
        rd.setDefaultBuckets(buckets);
        rd.setScheduler(scheduler);

        start = System.currentTimeMillis();
        rd.start();
        end = System.currentTimeMillis();
        System.out.println(end - start);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    rd.stop();
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
                Set<String> l = rd.buckets();
                for (String b : l) {
                    Set<String> set = new HashSet();
                    set.add(b);
                    rd.release(set);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            Thread.sleep(1000L);
        }
    }
}
