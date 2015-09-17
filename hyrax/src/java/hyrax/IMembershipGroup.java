package hyrax;

import java.util.Set;

public interface IMembershipGroup {

    void join();

    void leave();

    Set<String> members();

    interface Handler {
        void groupChanged(Set<String> oldPeers, Set<String> newPeers);
    }
}
