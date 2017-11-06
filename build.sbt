import org.hammerlab.sbt.deps.Dep

lazy val bgzf = project.settings(
  version := "1.0.0-SNAPSHOT",
  deps ++= Seq(
    case_app,
    cats,
    channel % "1.1.1",
    io % "3.0.0",
    iterators % "2.0.0-SNAPSHOT",
    math % "2.0.0",
    paths % "1.3.1",
    slf4j,
    spark_util % "2.0.1",
    stats % "1.1.1-SNAPSHOT"
  ),
  addSparkDeps,
  compileAndTestDeps += case_cli % "2.0.0"
).dependsOn(
  test_bams % "test"
)

lazy val check = project.settings(
  organization := "org.hammerlab.bam",
  version := "1.0.0-SNAPSHOT",
  deps ++= Seq(
    bytes % "1.0.3",
    case_app,
    cats,
    channel % "1.1.1",
    htsjdk,
    iterators % "2.0.0-SNAPSHOT",
    magic_rdds % "4.0.0-SNAPSHOT",
    io % "3.0.0",
    paths % "1.3.1",
    seqdoop_hadoop_bam,
    slf4j,
    spark_util % "2.0.1"
  ),
  fork := true,  // ByteRangesTest exposes an SBT bug that this works around; see https://github.com/sbt/sbt/issues/2824
  addSparkDeps,
  compileAndTestDeps ++= Seq(
    case_cli % "2.0.0",
    loci % "2.0.1"
  )
).dependsOn(
  bgzf,
  test_bams % "test"
)

lazy val cli = project.settings(
  organization := "org.hammerlab.bam",
  version := "1.0.0-SNAPSHOT",

  deps ++= Seq(
    bytes % "1.0.3",
    case_app,
    cats,
    channel % "1.1.1",
    hammerlab_hadoop_bam % "7.9.0",
    io % "3.0.0",
    iterators % "2.0.0-SNAPSHOT",
    magic_rdds % "4.0.0-SNAPSHOT",
    paths % "1.3.1",
    shapeless,
    spark_util % "2.0.1",
    stats % "1.1.1-SNAPSHOT",
    types % "1.0.1"
  ),

  compileAndTestDeps += case_cli % "2.0.0",

  // Bits that depend on the seqdoop module use org.hammerlab:hadoop-bam; make sure we don't get the org.seqdoop one.
  excludes += seqdoop_hadoop_bam,
  
  addSparkDeps,

  shadedDeps += shapeless,

  // Spark 2.1.0 (spark-submit is an easy way to run this library's Main) puts shapeless 2.0.0 on the classpath, but we
  // need 2.3.2.
  shadeRenames ++= Seq(
    "shapeless.**" → "shaded.shapeless.@1"
  ),

  main := "org.hammerlab.bam.Main",

  // It can be convenient to keep google-cloud-nio and gcs-connecter shaded JARs in lib/, though they're not checked into
  // git. However, we exclude them from the assembly JAR by default, on the assumption that they'll be provided otherwise
  // at runtime (by Dataproc in the case of gcs-connector, and by manually adding to the classpath in the case of
  // google-cloud-nio).
  assemblyExcludeLib,

  publishAssemblyJar
).dependsOn(
  bgzf,
  check,
  load,
  seqdoop,
  test_bams % "test"
)

lazy val load = project.settings(
  organization := "org.hammerlab.bam",
  version := "1.0.0-SNAPSHOT",

  // When running all tests in this project with `sbt test`, sometimes a Kryo
  // "Class is not registered: org.hammerlab.genomics.loci.set.LociSet" exception is thrown by
  // LoadBAMTest:"indexed disjoint regions"; this works around it.
  fork := true,

  deps ++= Seq(
    channel % "1.1.1",
    htsjdk,
    iterators % "2.0.0-SNAPSHOT",
    math % "2.0.0",
    paths % "1.3.1",
    reference % "1.4.0",
    seqdoop_hadoop_bam,
    slf4j,
    spark_util % "2.0.1"
  ),
  compileAndTestDeps += loci % "2.0.1",
  addSparkDeps,
  testDeps += magic_rdds % "4.0.0-SNAPSHOT"
).dependsOn(
  bgzf,
  check,
  test_bams % "test"
)

lazy val seqdoop = project.settings(
  organization := "org.hammerlab.bam",
  version := "1.0.0-SNAPSHOT",
  deps ++= Seq(
    channel % "1.1.1",
    htsjdk,
    paths % "1.3.1",
    hammerlab_hadoop_bam % "7.9.0"
  ),
  // Make sure we get org.hammerlab:hadoop-bam, not org.seqdoop
  excludes += seqdoop_hadoop_bam,
  addSparkDeps
).dependsOn(
  bgzf,
  check,
  test_bams % "test"
)

lazy val test_bams = project.settings(
  organization := "org.hammerlab.bam",
  name := "test-bams",
  version := "1.0.0-SNAPSHOT",
  deps ++= Seq(
    paths % "1.3.1",
    testUtils
  ),
  testDeps := Nil
)

// named this module "metrics" instead of "benchmarks" to work around bizarre IntelliJ-scala-plugin bug, cf.
// https://youtrack.jetbrains.com/issue/SCL-12628#comment=27-2439322
lazy val metrics = project.in(file("benchmarks")).settings(
  deps ++= Seq(
    paths % "1.3.1",
    bytes % "1.0.3"
  )
)

lazy val spark_bam =
  rootProject(
    bgzf,
    check,
    cli,
    load,
    seqdoop,
    test_bams
  )
