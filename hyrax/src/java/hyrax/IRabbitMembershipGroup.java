package hyrax;

import com.rabbitmq.client.Connection;

import java.util.concurrent.ScheduledExecutorService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Defines the parameters for configuring and managing a rabbit membership group
 * in a java-friendly way.
 */
public interface IRabbitMembershipGroup extends IMembershipGroup {

    void setConnection(Connection conn);

    /**
     * This is used as a base prefix to name all the rabbitmq configuration related
     * to the group. This is required.
     */
    void setName(String name);

    /**
     * The scheduler thread pool used to perform periodic actions to maintain
     * the list of peers. This is required.
     */
    void setScheduler(ScheduledExecutorService scheduler);

    /**
     * The period at which the distributor announces itself to its peers and
     * maintains its up-to-date set of known-healthy peers. Defaults to 1 minute.
     */
    void setPeersPeriod(long period);
    void setPeersUnits(TimeUnit unit);

    /**
     * If the distributor hasn't heard from a peer after this period, it considers
     * the peer to be gone and expires it from the set of known peers. Defaults to
     * 2 minutes.
     */
    void setExpirationPeriod(long period);
    void setExpirationUnits(TimeUnit unit);

    /**
     * Use this if you want to receive callbacks whenever the set of peers changes.
     * This is optional, its null by default.
     */
    void setHandler(IMembershipGroup.Handler handler);
}
