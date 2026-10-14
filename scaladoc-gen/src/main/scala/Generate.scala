import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Drives `opencode run` headlessly to fill the `TODO FILL IN` Scaladoc
  * markers that `todo-writer` left in the geny sources.
  *
  * Unlike the module1 generator, which produced a new project in an output
  * directory, this one edits source files in place. So the shape is a *loop*:
  * scan the tree, hand each marked file to a nested agent one at a time, then
  * rescan — a filled-in file no longer contains markers, so it drops out of
  * the next pass on its own.
  *
  * Usage:
  *   sbt "runMain Generate"                            # scan ../geny/geny/src
  *   sbt "runMain Generate --dry"                       # print the plan only
  *   sbt "runMain Generate --file Writable.scala"       # just one file
  *   sbt "runMain Generate --passes 2"                 # rescan and mop up
  *   sbt "runMain Generate --verify"                   # then run `sbt test`
  *
  * The prompt is read from prompt.txt next to this file if present, otherwise
  * a built-in default is used. `{{FILE}}` in the prompt is replaced with the
  * name of the file being documented. Override the model with
  * SCALADOC_GEN_MODEL.
  */
object Generate:
  val marker = "TODO FILL IN"

  private val defaultModel = "opencode/space-bunny-free"
  private val defaultSrc = "../geny/geny/src"
  private val defaultBuild = "../geny"

  val usage =
    """Usage: sbt "runMain Generate [options] [srcDir...]"
      |
      |  --src <dir>     source tree to scan; repeatable, or give bare words.
      |                  Defaults to ../geny/geny/src
      |  --file <name>   only files with this basename; repeatable
      |  --passes <n>    scan/do rounds; default 1. A file that still has
      |                  markers after a round is picked up again next pass
      |  --model <id>    model for the nested run; default opencode/space-bunny-free
      |                  (env SCALADOC_GEN_MODEL wins over this)
      |  --build <dir>   sbt project to test under --verify; default ../geny
      |  --timeout <min> per-file timeout in minutes; default 30
      |  --dry           print the plan and spawn nothing
      |  --no-preflight  skip the one-line agent check before editing
      |  --verify        run `sbt test` in --build afterwards
      |  --help          this text
      |""".stripMargin

  private val builtinPrompt =
    s"""Fill in every `$marker` Scaladoc placeholder in {{FILE}}, in place.
       |Read the declaration under each marker and document what the code
       |actually does. Replace the summary and every `@param` / `@tparam` /
       |`@return` placeholder with real Markdown prose. Do not change any
       |executable code, and leave no `$marker` behind.""".stripMargin

  /** Everything the loop needs, already resolved. */
  case class Config(
      srcDirs: List[Path],
      onlyFiles: Set[String],
      passes: Int,
      verify: Boolean,
      dry: Boolean,
      model: String,
      timeoutMinutes: Long,
      buildDir: Option[Path],
      preflight: Boolean
  )

  def main(args: Array[String]): Unit =
    if parseArgs(args.toList)._1.contains("help") then
      println(usage)
      return

    val config = resolve(parseArgs(args.toList))
    val files = markedFiles(config)

    println(s"model : ${config.model}")
    println(s"src   : ${config.srcDirs.mkString(", ")}")
    println(s"passes: ${config.passes}")
    println(s"dry   : ${config.dry}")
    println("-" * 60)

    if files.isEmpty then println("no markers found; nothing to do")
    else if !config.dry && config.preflight && !probe(config) then
      println("-" * 60)
      println("aborting before any edits; nothing was written")
      sys.exit(1)

    val failures = runPasses(config, files)

    println("-" * 60)
    val left = markedFiles(config)
    if left.isEmpty then println("all markers filled")
    else
      println(s"${left.size} file(s) still contain markers:")
      left.foreach(f => println(s"  $f"))

    val verifyCode =
      if config.verify then
        println("-" * 60)
        config.buildDir match
          case None =>
            println("no --build directory given; skipping verify")
            0
          case Some(dir) =>
            println(s"verifying with `sbt test` in $dir...")
            val code = run(shellIn(dir, "sbt", "-batch", "test"), dir, config.timeoutMinutes)
            println(s"sbt test exited $code")
            code
      else 0

    if failures.nonEmpty || verifyCode != 0 then sys.exit(1)

  /** Cheap canary: one trivial nested run before touching any real file.
    *
    * A broken agent used to cost one 500 per marked file and then a summary
    * that looked like a content problem. This turns that into one line, up
    * front, before a single source file has been rewritten.
    */
  private def probe(config: Config): Boolean =
    println("preflight: checking the agent can run at all...")
    val code = run(
      buildCommand(config.model, "Reply with exactly: OK", config.srcDirs.headOption.getOrElse(Path.of("."))),
      config.srcDirs.headOption.getOrElse(Path.of(".")),
      config.timeoutMinutes
    )
    if code == 0 then
      println("preflight: ok")
      true
    else
      println(s"preflight: agent exited $code, so no file was touched.")
      println("  Check the model id, credentials, and that opencode works by hand:")
      println(s"""    opencode run --standalone --model ${config.model} --auto 'hi'""")
      false

  /** One scan-and-do round. Returns the exit codes of the files that failed.
    *
    * Scanned before every pass rather than reusing the previous pass's list:
    * an agent that dies halfway through a file leaves that file partially
    * documented, and the only thing that notices is a fresh read of the tree.
    */
  private def runPasses(config: Config, firstFiles: List[Path]): List[Int] =
    var failures = List.empty[Int]
    var files = firstFiles

    for pass <- 1 to config.passes if files.nonEmpty do
      println(s"pass $pass: ${files.size} file(s)")
      files.foreach: file =>
        val remaining = countMarkers(file)
        println(s"  $file ($remaining marker(s))")
        if config.dry then ()
        else
          val code = run(
            buildCommand(config.model, prompt(file), file.getParent),
            file.getParent,
            config.timeoutMinutes
          )
          if code != 0 then failures = code :: failures
      files = if config.dry then Nil else markedFiles(config)

    failures

  /** Scala files under the source dirs that still contain markers.
    *
    * Sorted so a rerun walks the tree in the same order every time; `target`
    * and hidden directories are skipped so compiled copies and editor droppings
    * never get documented.
    */
  def markedFiles(config: Config): List[Path] =
    config.srcDirs
      .filter(Files.isDirectory(_))
      .flatMap: dir =>
        val stream = Files.walk(dir)
        try
          stream
            .iterator()
            .asScala
            .filter(Files.isRegularFile(_))
            .filter(_.toString.endsWith(".scala"))
            .filterNot(p => p.toString.split("/").exists(seg => seg == "target" || seg.startsWith(".")))
            .filter(p => countMarkers(p) > 0)
            .filter(p => config.onlyFiles.isEmpty || config.onlyFiles.contains(p.getFileName.toString))
            .toList
        finally stream.close()
      .distinct
      .sortBy(_.toString)

  /** Number of unfilled markers in a file; 0 when it cannot be read. */
  def countMarkers(file: Path): Int =
    try
      val text = Files.readString(file)
      // Scans non-overlapping occurrences, so a marker can never be counted
      // twice, and allocates nothing per match the way split would.
      var count = 0
      var from = 0
      var at = text.indexOf(marker, from)
      while at >= 0 do
        count += 1
        from = at + marker.length
        at = text.indexOf(marker, from)
      count
    catch case _: Exception => 0

  private def run(cmd: Seq[String], cwd: Path, timeoutMinutes: Long): Int =
    val pb = ProcessBuilder(cmd*)
    pb.directory(cwd.toFile)
    pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    val proc = pb.start()
    if !proc.waitFor(timeoutMinutes, TimeUnit.MINUTES) then
      proc.destroyForcibly()
      println(s"timed out after $timeoutMinutes minutes: ${cmd.mkString(" ")}")
      124
    else proc.exitValue()

  /** Runs through `zsh -c` with an explicit `cd`: `Process(cmd, dir)` has been
    * observed to leave the child in the sbt launch directory instead of the
    * requested one, which silently pointed the nested agent at the wrong tree.
    */
  private def shellIn(dir: Path, program: String, args: String*): Seq[String] =
    val cmd = (program +: args).map(shellQuote).mkString(" ")
    Seq("/bin/zsh", "-c", s"cd ${shellQuote(dir.toString)} && exec $cmd")

  /** The agent runs in the *file's* directory, so it edits a single file by
    * bare name and cannot wander into a sibling by accident.
    *
    * `--standalone` is required, not optional. Without it `opencode run`
    * connects to the long-lived background service, and a stale or wedged
    * service answers every request with a bare `UnexpectedStatus: 500` —
    * including a one-word prompt, so it looks like a model problem when it is
    * not one. A private per-run server cannot be stale.
    */
  private def buildCommand(model: String, prompt: String, cwd: Path): Seq[String] =
    shellIn(
      cwd,
      "opencode", "run",
      "--standalone",
      "--model", model,
      "--title", "scaladoc-gen",
      "--auto", // headless run cannot answer interactive permission prompts
      prompt
    )

  private def shellQuote(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"

  /** prompt.txt next to this file, else the one in the sbt launch directory,
    * else the built-in default. `{{FILE}}` becomes the file name.
    */
  private def prompt(file: Path): String =
    val template = List(
      Path.of("prompt.txt"),
      Path.of("scaladoc-gen/prompt.txt")
    )
      .find(Files.isRegularFile(_))
      .map(Files.readString)
      .getOrElse(builtinPrompt)
    template.replace("{{FILE}}", file.getFileName.toString)

  /** Options that take no value. Without this list the parser has to guess
    * whether the word after `--dry` is its value or a positional source
    * directory, and `--dry ../geny/src` silently becomes `--dry=../geny/src`.
    *
    * Not private so ScanCheck can exercise it directly.
    */
  val booleanFlags: Set[String] =
    Set("dry", "verify", "help", "no-preflight")

  /** `--key value` options plus bare words. Flags and values both collect into
    * `opts`, keyed by their name; bare words come back as positionals.
    *
    * Not private so ScanCheck can exercise it directly.
    */
  def parseArgs(args: List[String]): (Map[String, List[String]], List[String]) =
    def loop(rest: List[String], opts: Map[String, List[String]], positional: List[String])
        : (Map[String, List[String]], List[String]) =
      rest match
        case Nil => (opts, positional.reverse)
        case key :: tail if key.startsWith("--") =>
          val name = key.drop(2)
          tail match
            // a value-taking option whose next word is not itself an option
            case value :: more if !booleanFlags.contains(name) && !value.startsWith("--") =>
              loop(more, opts.updated(name, value :: opts.getOrElse(name, Nil)), positional)
            case _ =>
              loop(tail, opts.updated(name, "" :: opts.getOrElse(name, Nil)), positional)
        case word :: tail =>
          loop(tail, opts, word :: positional)

    loop(args, Map.empty, Nil)

  private def resolve(parsed: (Map[String, List[String]], List[String])): Config =
    val (opts, positionals) = parsed
    def one(key: String): Option[String] = opts.get(key).flatMap(_.headOption)
    def many(key: String): List[String] = opts.getOrElse(key, Nil).reverse

    val srcDirs = (positionals.reverse ++ many("src").reverse)
      .map(d => Path.of(d).toAbsolutePath.normalize())

    Config(
      srcDirs = srcDirs match
        case Nil => List(Path.of(defaultSrc).toAbsolutePath.normalize())
        case ds  => ds,
      onlyFiles = many("file").toSet,
      passes = one("passes").map(_.toInt).getOrElse(1),
      verify = opts.contains("verify"),
      dry = opts.contains("dry"),
      model = sys.env.getOrElse("SCALADOC_GEN_MODEL", one("model").getOrElse(defaultModel)),
      timeoutMinutes = one("timeout").map(_.toLong).getOrElse(30L),
      buildDir = one("build")
        .map(d => Path.of(d).toAbsolutePath.normalize())
        .orElse {
          val fallback = Path.of(defaultBuild).toAbsolutePath.normalize()
          Option.when(Files.isRegularFile(fallback.resolve("build.sbt")))(fallback)
        },
      preflight = !opts.contains("no-preflight")
    )