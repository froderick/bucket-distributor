package org.funtastic.hyrax;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import hyrax.IMembershipGroup;
import hyrax.RabbitMembershipGroup;

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

        final RabbitMembershipGroup g = new RabbitMembershipGroup();
        g.setConnection(c);
        g.setName("test");
        g.setScheduler(scheduler);
        g.setHandler(new IMembershipGroup.Handler() {
            public void groupChanged(Set<String> oldPeers, Set<String> newPeers) {
                System.out.println("G: " + oldPeers + "/" + newPeers);
            }
        });
        g.join();

        final RabbitMembershipGroup h = new RabbitMembershipGroup();
        h.setConnection(c);
        h.setName("test");
        h.setScheduler(scheduler);
        h.setHandler(new IMembershipGroup.Handler() {
            public void groupChanged(Set<String> oldPeers, Set<String> newPeers) {
                System.out.println("H:" + oldPeers + "/" + newPeers);
            }
        });
        h.join();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    g.leave();
                    h.leave();
                    scheduler.shutdownNow();
                    c.close();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        while (true) {
            System.out.println(g.members());
            Thread.sleep(1000);
        }
    }
}
