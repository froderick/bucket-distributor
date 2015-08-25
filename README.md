# Hyrax

Hyrax is a collection of tools for coordinating state in a distributed
environment.

The only tool currently implemented is the bucket Distributor abstraction.
It has one implementation, using RabbitMQ. See [dist](hyrax/doc/dist.md) for more 
information on this.

A tool that I plan to research here is a competing consumer design based on
using MongoDB 3.0 for persistence and the distributor abstraction for avoiding
contention between consumers in MongoDB. This is a simple way to model this
pattern rather than resorting to something more complex like kafka if the
performance demands do not require it.

The name for this project comes from a [small sub-saharan creature](https://en.wikipedia.org/wiki/Hyrax) often mistaken for a rodent, but which actually has more in common with elephants and manatees. Why? Because I needed a short, memorable name with a mascot.

