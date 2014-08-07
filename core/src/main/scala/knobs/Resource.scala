package knobs

import java.net.URI
import java.io.File
import scalaz._
import scalaz.syntax.show._
import scalaz.concurrent.Task

/** Resources from which configuration files can be loaded */
trait Resource[R] extends Show[R] {
  /**
    * Returns a resource that has the given `child` path resolved against `r`.
    * If the given path is absolute (not relative), a new resource to that path should be returned.
    * Otherwise the given path should be appended to this resource's path.
    * For example, if this resource is the URI "http://tempuri.org/foo/", and the given
    * path is "bar/baz", then the resulting resource should be the URI "http://tempuri.org/foo/bar/baz"
    */
  def resolve(r: R, child: Path): R

  /** Loads a resource, returning a list of config directives in `Task` */
  def load(path: Worth[R]): Task[List[Directive]]
}

/** An existential resource. Equivalent to the (Haskell-style) type `exists r. Resource r ⇒ r` */
abstract class ResourceBox {
  type R
  val R: Resource[R]
  val resource: R
  def or(b: ResourceBox) = Resource.box(Resource.or(resource, b.resource)(R, b.R))
  override def equals(other: Any) = other.asInstanceOf[ResourceBox].resource == resource
  override def hashCode = resource.hashCode
}

object ResourceBox {
  def apply[R:Resource](r: R) = Resource.box(r)

  implicit def resourceBoxShow: Show[ResourceBox] = new Show[ResourceBox] {
    override def shows(b: ResourceBox): String = b.R.shows(b.resource)
  }
}

object FileResource {
  def apply(f: File): ResourceBox = Resource.box(f)
}

object ClassPathResource {
  def apply(s: String): ResourceBox = Resource.box(Resource.ClassPath(s))
}

object SysPropsResource {
  def apply(p: Pattern): ResourceBox = Resource.box(p)
}

object Resource {
  type FallbackChain = OneAnd[Vector, ResourceBox]

  def apply[B](r: B)(implicit B: Resource[B]): ResourceBox = box(r)

  // Box up a resource with the evidence that it is a resource.
  def box[B](r: B)(implicit B: Resource[B]): ResourceBox =
    new ResourceBox {
      type R = B
      val R = B
      val resource = r
    }

  def resolveName(parent: String, child: String) =
    if (child.head == '/') child
    else parent.substring(0, parent.lastIndexOf('/') + 1) ++ child

  def loadFile[R:Resource](path: Worth[R], load: Task[String]): Task[List[Directive]] = {
    import ConfigParser.ParserOps
    for {
      es <- load.attempt
      r <- es.fold(ex =>
        path match {
          case Required(_) => Task.fail(ex)
          case _ => Task.now(Nil)
        }, s => for {
          p <- Task.delay(ConfigParser.topLevel.parse(s)).attempt.flatMap {
            case -\/(ConfigError(_, err)) =>
              Task.fail(ConfigError(path.worth, err))
            case -\/(e) => Task.fail(e)
            case \/-(a) => Task.now(a)
          }
          r <- p.fold(err => Task.fail(ConfigError(path.worth, err)),
                      ds => Task.now(ds))
        } yield r)
    } yield r
  }

  implicit def fileResource: Resource[File] = new Resource[File] {
    def resolve(r: File, child: String): File =
      new File(resolveName(r.getPath, child))
    def load(path: Worth[File]) =
      loadFile(path, Task(scala.io.Source.fromFile(path.worth).mkString))
    override def shows(r: File) = r.toString
  }

  implicit def uriResource: Resource[URI] = new Resource[URI] {
    def resolve(r: URI, child: String): URI = r resolve new URI(child)
    def load(path: Worth[URI]) =
      loadFile(path, Task(scala.io.Source.fromFile(path.worth).mkString + "\n"))
    override def shows(r: URI) = r.toString
  }

  sealed trait ClassPath
  def ClassPath(s: String): String @@ ClassPath = Tag[String, ClassPath](s)

  implicit def classPathResource: Resource[String @@ ClassPath] = new Resource[String @@ ClassPath] {
    def resolve(r: String @@ ClassPath, child: String) =
      ClassPath(resolveName(r, child))
    def load(path: Worth[String @@ ClassPath]) = {
      val r = path.worth
      loadFile(path, Task(getClass.getClassLoader.getResourceAsStream(r)) flatMap { x =>
        if (x == null) Task.fail(new java.io.FileNotFoundException(r + " (on classpath)"))
        else Task(scala.io.Source.fromInputStream(x).mkString)
      })
    }
    override def shows(r: String @@ ClassPath) = {
      val res = getClass.getClassLoader.getResource(r)
      if (res == null)
        "Non-existing classpath resource $r"
      else
        res.toURI.toString
    }
  }

  implicit def sysPropsResource: Resource[Pattern] = new Resource[Pattern] {
    import ConfigParser.ParserOps
    def resolve(p: Pattern, child: String) = p match {
      case Exact(s) => Exact(s"$p.$s")
      case Prefix(s) => Prefix(s"$p.$s")
    }
    def load(path: Worth[Pattern]) = {
      val pat = path.worth
      for {
        ds <- Task(sys.props.toMap.filterKeys(pat matches _).map {
                   case (k, v) => Bind(k, ConfigParser.value.parse(v).toOption.getOrElse(CfgText(v)))
                 })
        r <- (ds.isEmpty, path) match {
          case (true, Required(_)) =>
            Task.fail(new ConfigError(path.worth, "Required system properties $pat not present."))
          case _ => Task(ds.toList)
        }
      } yield r
    }
    override def shows(r: Pattern): String = s"System properties $r.*"
  }

  implicit def fallbackResource: Resource[FallbackChain] = new Resource[FallbackChain] {
    def resolve(p: FallbackChain, child: String) = {
      val OneAnd(a, as) = p
      OneAnd(box(a.R.resolve(a.resource, child))(a.R), as.map(b =>
        box(b.R.resolve(b.resource, child))(b.R)
      ))
    }
    def load(path: Worth[FallbackChain]) = {
      val err = Task.fail {
        ConfigError(path.worth, s"Failed to load.")
      }
      val OneAnd(r, rs) = path.worth
      (r +: rs).foldRight(if (path.isRequired) err else Task.now(List[Directive]()))((x, y) =>
        x.R.load(Required(x.resource)) or y
      )
    }
    override def shows(r: FallbackChain) = {
      val OneAnd(a, as) = r
      (a +: as).map(_.show).mkString(" or ")
    }
  }

  /**
   * Returns a resource that tries `r1`, and if it fails, falls back to `r2`
   */
  def or[R1: Resource, R2: Resource](r1: R1, r2: R2): FallbackChain =
    OneAnd(box(r1), Vector(box(r2)))

  implicit class ResourceOps[R:Resource](r: R) {
    def or[R2:Resource](r2: R2) = Resource.or(r, r2)

    def resolve(child: String): R = implicitly[Resource[R]].resolve(r, child)

    def required: KnobsResource = Required(box(r))

    def optional: KnobsResource = Optional(box(r))
  }
}

