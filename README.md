# hadoop-bam

Extension of HadoopGenomics/hadoop-bam

Implements [a `BAMInputFormat`](src/main/scala/org/hammerlab/hadoop_bam/BAMInputFormat.scala) that fetches BAM-ranges in parallel using a configurable number of worker-threads:

[![Scaling log-log plot](https://cl.ly/3C0k2Y203U0i/image%20(22).png)](https://docs.google.com/a/hammerlab.org/spreadsheets/d/11c6T-HxR7bMdPOeS6l3n4klBuC9PhgrR5JcSg2qa_H4/edit?usp=sharing)

## Impetus

With [the upstream BAMInputFormat](https://github.com/HadoopGenomics/Hadoop-BAM/blob/7.8.0/src/main/java/org/seqdoop/hadoop_bam/BAMInputFormat.java), computing splits on a Google Cloud Storage (GCS)-resident BAM typically took O(minutes), e.g. 2mins for a 20GB BAM and 15mins on a 178GB BAM in recent benchmarks.

During this time, the driver node fetches data from each split-offset returned by `FileInputFormat`, typically ≈4 64KB BGZF blocks (or 256KB) every 32MB, 64MB, or 128MB according to the HDFS-block-size being used (or simulated in the case of a [`GoogleHadoopFS`](https://github.com/GoogleCloudPlatform/bigdata-interop/blob/v1.6.1/gcs/src/main/java/com/google/cloud/hadoop/fs/gcs/GoogleHadoopFS.java)). 

Each network request and resulting split-computation happens in serial, and the network requests exhibit a high, fixed latency-cost.
 
By having N threads parallelize the work of [fetching the start of each `FileInputFormat`-split], near-perfect linear scaling has been observed, up to 64 threads bringing split-computation from 15mins to 20s on a 178GB BAM and from 2mins to 4s on a 22GB BAM; both were benchmarked on a 4-core GCE `n1-standard-4` VM.

## Using

### Running Locally
To build/run locally, try [the sample `Main` contained in this library](src/main/scala/org/hammerlab/hadoop_bam/Main.scala):

```bash
sbt assembly
JAR=target/scala-2.11/hadoop-bam-assembly-1.0.0-SNAPSHOT.jar
time spark-submit "$JAR" -n 32 <BAM>
```

This print all computed splits as well as a timing stat for just the split-computation (whereas the stats output by `time` will include various app initialization-overhead time as well).


