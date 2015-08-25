# bucket-distributor

Makes use of rabbit queues for coordinated distribution of hash buckets.

##What problem is this solving?

One example is a scheduler application. A scheduler is fundamentally a while
loop that iterates over a set of tasks, filtering by those that are ready to be
performed. It is a singleton, and can become a bottleneck.  One way to scale it
is to hash all the items into buckets such that they can be grouped together by
their bucket. N schedulers can then run against N buckets that are assigned
specifically to them, without running over each other or causing data
contention.

1:1 assignment between schedulers and buckets is easy to implement, and works
well until you need to add more schedulers. With this design a rehash is
required which can be expensive and cause service interruptions depending on
the implementation.  Consistent hashing provides a way to add schedulers after
the fact without requiring a re-hash. It does this by hashing many more buckets
than schedulers, and then assigning those buckets to schedulers.

At this point we can add new schedulers, but we have a configuration problem.
We must now maintain configuration as to which scheduler servers are assigned
to which buckets. Also, if one (or more) of the scheduler servers dies, we will
need to reassign its buckets to other schedulers. With only a handful of
scheduler servers, this might not be a big deal, but with many this can be a
maintenance nightmare.

This BucketDistributor
implementation uses rabbit to efficiently distribute hash buckets across an
arbitrary number of scheduler servers (or whatever the use case) automatically
based on the number of buckets available and the number of scheduler servers
active. If the number of active scheduler servers changes, the bucket
partitions sizes are automatically adjusted such that each scheduler still
receives <code>buckets / schedulers</code> number of buckets.  This is designed
to work even if the scheduler servers are shutdown uncleanly (think <code>kill
-9</code>).

##Implementation Notes

Note that the buckets are pushed to clients before they ask for them. For the
duration that this distributor is active, it will continue to accumulate
buckets up to the specified limit. It will retain them until release() is
called, so use cases will generally involve a polling loop to check the
contents of buckets(), handle them, and release() them.

Because basicReject() is used to put buckets back into the queue, it is likely
that the same distributor instance may receive the same set of buckets
repeatedly. However, this is not guaranteed and you shouldn't base any code on
the assumption that a distributor always gets the same partition's worth of
buckets. A partition is just the maximum number of buckets the distributor
expects to receive at once.

This distributor is threadsafe.

##The Perils of Distributed Computing

As this uses rabbit, this distributor offers no guarantees in the face of a
network partitions. It would be easy for a scheduler server from my earlier
example to receive a partition of buckets and then lose its connection to
rabbit. In this scenario, while it is processing its partition, from rabbit's
perspective the unacked messages go back onto the queue. Then, another
scheduler still connected to rabbit receives those same buckets and begins
processing them also.

This issue can be mitigated by making processing work against a partition take
as little time as possible, or processing in smaller batches. That way the
processor spends as little time as possible in its critical section and any
damage from this scenario would be minimized.

If CP is what you're looking for, go back, this isn't what you want.
Try Zookeeper.

##Why use this instead of Zookeeper?

Zookeeper is strongly consistent (CP). You want this if you want to
guarantee(ish) that your data will never go sideways, at the cost of becoming
unavailable in certain circumstances. Consistency is expensive, and not all
distributed consensus has to be this precise.

This implementation has two advantages. First, its available rather than
consistent. Network partitions don't keep any apps that can connect to rabbit
from doing something sensible. Second, its fast. (18k buckets delivered/sec
on my personal laptop in an extremely ad-hoc and questionable testing 
environment). Additionally, if you already have rabbit set up in your
environment and you're comfortable with the extra load this might place on
it, then this is easy to get started with.

##Re-Hashing

If you need to completely rehash your data, delete the bucketQueue, update the
constructor with the new buckets, and redeploy your code. On start(), the
distributor automatically creates the bucketQueue if it doesn't exist and
populates it with the configured defaults. This is done using an exclusive
queue as a mutex such that it shouldn't happen more than once or on more than
one distributor at a time.

**The following works in the java version, but this isn't implemented in hyrax
yet**

> If you want to inspect the contents of the bucketQueue without having to shut
> down all the consuming client applications in a cluster in order to do this,
> there is a simple Command-and-Control api you can use. Post a message to the
> <b>broadcastExchange</b> containing 'pause', and you will be able to
> temporarily disconnect all the consumers from the bucketQueue until you're done
> fussing with it. When you're done, submit another message to that exchange
> containing 'resume', and you'll be back on your way.

##Remaining Work

* TODO: publish telemetry (via riemann).
* TODO: what do alerts and ops dashboards look like for a system like this?
* TODO: hot reloading of config parameters
* TODO: finish writing tests



