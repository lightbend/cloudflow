# Windows implementation in Cloudflow

Windowing is an important concept in streaming. 
[Streaming 101](https://www.oreilly.com/radar/the-world-beyond-batch-streaming-101/) 
is a great source for detailed information. 
There is also an excellent [blog post](https://softwaremill.com/windowing-data-in-akka-streams/) 
summarizing the idea supported by examples. 
In a nutshell Windows split the stream into “buckets” of finite size, over which we can apply computations.

There are two general groups of windows `session-based` and `time-based` windows. 

Time-based windows group data basing on time. Typically we can choose either event-time - 
a timestamp derived from each data element, or processing-time - the wall-clock time at the moment the event is processed.
There are two main types of time-based windows: 
* `Tumbling windows`have a fixed (time) size and do not overlap. In this cases time is divided into non-overlapping parts 
and each data element belongs to a single window. 
* `Sliding windows`, parametrised by length and step. These windows overlap, and each data element can belong to multiple windows. 

Session windows can vary in length, and are grouped based on e.g. a session id derived from each data element. 
Alternatively, session window can be time based and closes when it does not receive elements for a certain 
period of time, i.e., when a gap of inactivity occurred.

Additional issue with windowing is that events often don’t arrive in order (e.g. if the data comes from 
a mobile or IoT device, there might be connectivity breaks or the clocks on each device might differ). 
We have to deal with that somehow, in a way that keeps memory usage under control: we can only keep a 
limited number of windows "open", that is accepting data. At some point old windows have to be "closed" 
(and discarded, thus freeing memory), and that is usually done through a watermarking mechanism. 
A watermark specifies that there will be no events older than a given time; or that if there will be, they will be dropped. 
For example, we can say that at any time the watermark is the timestamp of the newest event minus 10 minutes. 
Or maybe we have well-behaved data and they always arrive in order? Then our live is a bit easier.
Note that watermarks only apply to event-time case and impact both performance and memory usage.

Currently Akka Stremas provides several operators that can be (sort off) used for windowing:
* [groupedWithin](https://doc.akka.io/docs/akka/current/stream/operators/Source-or-Flow/groupedWithin.html): 
Chunk up the stream into groups of elements received within a time window, or limited by the number of the 
elements. This can be considered as a tumbling window.
* [sliding](https://doc.akka.io/docs/akka/current/stream/operators/Source-or-Flow/sliding.html): 
Provide a sliding window over the incoming stream and pass the windows as groups of elements downstream.
* [sessionWindow](https://index.scala-lang.org/efekahraman/akka-streams-session-window/akka-stream-session-window/0.1.0?target=_2.12)
implement time-based session windows - helps to identify periods of activity.

Although these operators are very usefull, they are based on the amount of elements - not directly time
and do not work with `FlowWithCommittableContext` used for majority of cloudflow implementations.

The following windows are implemented:
* TumblingWindow] - implements tumbling window, that can be used as following:
````
sourceWithCommittableContext(in)
      .via(TumblingWindow[SimpleMessage](duration = 3.second, time_extractor = (msg) ⇒ msg.ts, watermark = 1.2.second))
````
* SlidingWindow - implement sliding window, that can be used as following:
````
 sourceWithCommittableContext(in)
       .via(SlidingWindow[SimpleMessage](duration = 3.second, slide = 1.5.second, time_extractor = (msg) ⇒ msg.ts, watermark = 1.2.second)) 
````
* SessionInactivityWindows - implements session window based on inactivity, that can be used as following:
````
sourceWithCommittableContext(in)
      .via(SessionInactivityWindow[SimpleMessage](inactivity = 1.second, time_extractor = (msg) ⇒ msg.ts))                                           
 ````
* SessionValueWindow] - implements session window based on session value (string), that can be used as following:
````
sourceWithCommittableContext(in)
      .via(SessionValueWindow[SimpleMessageSession](session_extractor = (msg) ⇒ msg.session, inactivity = 1.second)) 
````

All of these implementation produce `CommittableOffsetBatch`. Due to the current limitations, window
usage requires the following additions to your code:
````
   val committerSettings = CommitterSettings(system).withCommitWhen(CommitWhen.OffsetFirstObserved)

    ......................

    .to(committableSink(committerSettings))
````

Copyright (C) 2020 Lightbend Inc. (https://www.lightbend.com).

