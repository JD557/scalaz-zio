package scalaz.zio.stream

import scalaz.zio._

trait StreamChunk[+E, @specialized +A] { self =>
  val chunks: Stream[E, Chunk[A]]

  def foldLazy[E1 >: E, A1 >: A, S](s: S)(cont: S => Boolean)(f: (S, A1) => IO[E1, S]): IO[E1, S] =
    chunks.foldLazy[E1, Chunk[A1], S](s)(cont) { (s, as) =>
      as.foldMLazy(s)(cont)(f)
    }

  def foldLeft[A1 >: A, S](s: S)(f: (S, A1) => S): IO[E, S] =
    foldLazy(s)(_ => true)((s, a) => IO.now(f(s, a)))

  /**
   * Executes an effectful fold over the stream of chunks.
   */
  def foldLazyChunks[E1 >: E, A1 >: A, S](s: S)(cont: S => Boolean)(f: (S, Chunk[A1]) => IO[E1, S]): IO[E1, S] =
    chunks.foldLazy[E1, Chunk[A1], S](s)(cont)(f)

  def flattenChunks: Stream[E, A] =
    chunks.flatMap(Stream.fromChunk)

  /**
   * Runs the sink on the stream to produce either the sink's result or an error.
   */
  final def run[E1 >: E, A0, A1 >: A, B](sink: Sink[E1, A0, Chunk[A1], B]): IO[E1, B] =
    chunks.run(sink)

  /**
   * Filters this stream by the specified predicate, retaining all elements for
   * which the predicate evaluates to true.
   */
  def filter(pred: A => Boolean): StreamChunk[E, A] =
    StreamChunk[E, A](self.chunks.map(_.filter(pred)))

  def filterNot(pred: A => Boolean): StreamChunk[E, A] = filter(!pred(_))

  final def foreach0[E1 >: E](f: A => IO[E1, Boolean]): IO[E1, Unit] =
    chunks.foreach0[E1] { as =>
      as.foldM(true) { (p, a) =>
        if (p) f(a)
        else IO.point(p)
      }
    }

  final def foreach[E1 >: E](f: A => IO[E1, Unit]): IO[E1, Unit] =
    foreach0[E1](f(_).const(true))

  final def withEffect[E1 >: E](f0: A => IO[E1, Unit]): StreamChunk[E1, A] =
    StreamChunk(chunks.withEffect[E1] { as =>
      as.traverse_(f0)
    })

  def dropWhile(pred: A => Boolean): StreamChunk[E, A] =
    StreamChunk(new Stream[E, Chunk[A]] {
      override def foldLazy[E1 >: E, A1 >: Chunk[A], S](s: S)(cont: S => Boolean)(f: (S, A1) => IO[E1, S]): IO[E1, S] =
        self
          .foldLazyChunks[E1, A, (Boolean, S)](true -> s)(tp => cont(tp._2)) {
            case ((true, s), as) =>
              val remaining = as.dropWhile(pred)

              if (remaining.length > 0) f(s, remaining).map(false -> _)
              else IO.now(true                                    -> s)
            case ((false, s), as) => f(s, as).map(false -> _)
          }
          .map(_._2.asInstanceOf[S]) // Cast is redundant but unfortunately necessary to appease Scala 2.11
    })

  def takeWhile(pred: A => Boolean): StreamChunk[E, A] =
    StreamChunk(new Stream[E, Chunk[A]] {
      override def foldLazy[E1 >: E, A1 >: Chunk[A], S](s: S)(cont: S => Boolean)(f: (S, A1) => IO[E1, S]): IO[E1, S] =
        self
          .foldLazyChunks[E1, A, (Boolean, S)](true -> s)(tp => tp._1 && cont(tp._2)) { (s, as) =>
            val remaining = as.takeWhile(pred)

            if (remaining.length == as.length) f(s._2, as).map(true -> _)
            else f(s._2, remaining).map(false                       -> _)
          }
          .map(_._2.asInstanceOf[S]) // Cast is redundant but unfortunately necessary to appease Scala 2.11
    })

  def zipWithIndex: StreamChunk[E, (A, Int)] =
    StreamChunk(
      new Stream[E, Chunk[(A, Int)]] {
        override def foldLazy[E1 >: E, A1 >: Chunk[(A, Int)], S](
          s: S
        )(cont: S => Boolean)(f: (S, A1) => IO[E1, S]): IO[E1, S] =
          chunks
            .foldLazy[E1, Chunk[A], (S, Int)]((s, 0))(tp => cont(tp._1)) {
              case ((s, index), as) =>
                val zipped = as.zipWithIndex0(index)

                f(s, zipped).map((_, index + as.length))
            }
            .map(_._1.asInstanceOf[S]) // Cast is redundant but unfortunately necessary to appease Scala 2.11
      }
    )

  final def mapAccum[@specialized S1, @specialized B](s1: S1)(f1: (S1, A) => (S1, B)): StreamChunk[E, B] =
    StreamChunk(chunks.mapAccum(s1)((s1: S1, as: Chunk[A]) => as.mapAccum(s1)(f1)))

  def map[@specialized B](f: A => B): StreamChunk[E, B] =
    StreamChunk(chunks.map(_.map(f)))

  def mapConcat[B](f: A => Chunk[B]): StreamChunk[E, B] =
    StreamChunk(chunks.map(_.flatMap(f)))

  final def mapM[E1 >: E, B](f0: A => IO[E1, B]): StreamChunk[E1, B] =
    StreamChunk(chunks.mapM(_.traverse(f0)))

  final def flatMap[E1 >: E, B](f0: A => StreamChunk[E1, B]): StreamChunk[E1, B] =
    StreamChunk(chunks.flatMap(_.map(f0).foldLeft[Stream[E1, Chunk[B]]](Stream.empty)((acc, el) => acc ++ el.chunks)))

  final def ++[E1 >: E, A1 >: A](that: StreamChunk[E1, A1]): StreamChunk[E1, A1] =
    StreamChunk(chunks ++ that.chunks)

  final def toQueue[E1 >: E, A1 >: A](capacity: Int = 1): Managed[Nothing, Queue[Take[E1, Chunk[A1]]]] =
    chunks.toQueue(capacity)

  final def toQueueWith[E1 >: E, A1 >: A, Z](f: Queue[Take[E1, Chunk[A1]]] => IO[E1, Z], capacity: Int = 1): IO[E1, Z] =
    toQueue[E1, A1](capacity).use(f)
}

object StreamChunk {
  def apply[E, A](chunkStream: Stream[E, Chunk[A]]): StreamChunk[E, A] =
    new StreamChunk[E, A] {
      val chunks = chunkStream
    }

  def fromChunks[A](as: Chunk[A]*): StreamChunk[Nothing, A] =
    StreamChunkPure(StreamPure.fromIterable(as))

  def point[A](as: => Chunk[A]): StreamChunk[Nothing, A] =
    StreamChunkPure(StreamPure.point(as))

  def empty: StreamChunk[Nothing, Nothing] = StreamChunkPure(StreamPure.empty)
}
