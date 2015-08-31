Desired Improvements
====================

1. async `release-buckets!`

    Currently `acquire-buckets!` is non-blocking by nature, but
    `release-buckets!` does block. It synchronously calls out to rabbit to send
    message rejections. This is odd, and unneccesary. To make them consistent
    we could acknowlege the release from the client, and then buffer up that
    information, delivering it to rabbit later. This adds the need for a worker
    that handles the asynchronous communication. We could use the scheduler
    already passed in for this purpose. The workflow would be:

    - client calls `release-buckets!`
    - we record that the supplied buckets are waiting to be rejected back to rabbit (atom)
    - we schedule a recurring release process to occur if one isn't already 
      running (now, then periodically until we are successful) 

    This isn't much of a stretch, we're set up nicely to make this happen. This
    feature is useful particularly because the lack of it significantly
    complicates any client-side code that uses the Distributor as it must take
    release faliures/retries into account. Adding this feature makes this
    abstraction less leaky.
