package com

import gremlin.scala._

import scalaz.Memo._
import scalaz.Free._
import scalaz._
import scalaz.concurrent.{ Future, Task }

package object graf {
  type Graf[A] = FreeC[GrafOp, A]
  type GrafR[A] = ScalaGraph[Graph] ⇒ OneTimeTask[A]

  sealed trait GrafOp[+A]

  case object GetGraph extends GrafOp[ScalaGraph[Graph]]

  case class OneTimeTask[A](override val get: Future[Throwable \/ A]) extends Task[A](get) {
    private val memo = immutableHashMapMemo {
      get: Future[Throwable \/ A] ⇒
        get.run match {
          case -\/(e) ⇒ throw e
          case \/-(a) ⇒ a
        }
    }

    def flatMap[B](f: (A) ⇒ OneTimeTask[B]): OneTimeTask[B] =
      OneTimeTask(get flatMap {
        case -\/(e) ⇒ Future.now(-\/(e))
        case \/-(a) ⇒ Task.Try(f(a)) match {
          case e @ -\/(_) ⇒ Future.now(e)
          case \/-(task) ⇒ task.get
        }
      })

    override def map[B](f: (A) ⇒ B): OneTimeTask[B] = OneTimeTask(get map { _ flatMap { a ⇒ Task.Try(f(a)) } })

    override def run: A = memo(get)
  }

  def G = liftFC(GetGraph)

  implicit val toState: GrafOp ~> GrafR = new (GrafOp ~> GrafR) {
    override def apply[A](fa: GrafOp[A]): GrafR[A] = fa match {
      case GetGraph ⇒ g ⇒ OneTimeTask(Future.delay(\/-(g)))
    }
  }

  implicit val GrafRMonad = new Monad[GrafR] {

    override def bind[A, B](fa: GrafR[A])(f: (A) ⇒ GrafR[B]): GrafR[B] = g ⇒ fa(g) flatMap (a ⇒ f(a)(g))

    override def point[A](a: ⇒ A): GrafR[A] = g ⇒ OneTimeTask(Future.delay(\/-(a)))
  }

  implicit val VertexShow = new Show[Vertex] {
    override def shows(f: Vertex): String = {
      val name = f.property("name")
      if (name.isPresent) s"v[${f.id}:${f.label}:${name.value}]"
      else s"v[${f.id}:${f.label}]"
    }
  }

  implicit val EdgeShow = new Show[Edge] {
    override def shows(f: Edge): String = {
      val name = f.property("name")
      val es = if (name.isPresent) s"e[${f.id}:${f.label}:${name.value}]"
      else s"e[${f.id}:${f.label}]"
      implicitly[Show[Vertex]].shows(f.outVertex) + s" --- $es --> " + implicitly[Show[Vertex]].shows(f.inVertex)
    }
  }

  implicit class GrafFunctions[A](g: Graf[A]) extends GrafR[A] {
    def bind(graph: Graph) = apply(graph.asScala)

    override def apply(graph: ScalaGraph[Graph]): OneTimeTask[A] =
      runFC(g)(toState).apply(graph)
  }
}
