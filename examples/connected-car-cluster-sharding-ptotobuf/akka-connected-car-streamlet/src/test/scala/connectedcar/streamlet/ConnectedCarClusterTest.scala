package connectedcar.streamlet

import akka.actor._
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.typed.{ Cluster, Join }
import akka.stream.scaladsl._
import akka.testkit._
import cloudflow.akkastream.testkit.scaladsl._
import connectedcar.data.ConnectedCarAgg
import connectedcar.streamlets.ConnectedCarCluster
import connectedcar.streamlets.RawCarDataGenerator.generateCarERecord
import org.scalatest._

class ConnectedCarClusterTest extends WordSpec with MustMatchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("AkkaStreamletSpec")
  private val cluster                      = Cluster(system.toTyped)

  override def beforeAll: Unit =
    cluster.manager ! Join(cluster.selfMember.address)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  "A ConnectedCarCluster streamlet" should {

    val testkit = AkkaStreamletTestKit(system)

    "Allow for creating a 'flow processor'" in {
      val record = generateCarERecord()
      val data   = Vector(record)

      val agg          = ConnectedCarAgg(record.carId, record.driver, record.speed, 1)
      val expectedData = Vector(agg)
      val source       = Source(data)
      val proc         = new ConnectedCarCluster
      val in           = testkit.inletFromSource(proc.in, source)
      val out          = testkit.outletAsTap(proc.out)

      testkit.run(proc, in, out, () ⇒ out.probe.receiveN(1) mustBe expectedData.map(d ⇒ proc.out.partitioner(d) -> d))

      out.probe.expectMsg(Completed)
    }
  }

}
