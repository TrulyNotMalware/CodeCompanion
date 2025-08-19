package dev.notypie.domain.command

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class EventQueueTest: BehaviorSpec(body={

    given("An empty event queue"){
        val eventQueue = createDomainEventQueue()

        then("should be empty"){
            eventQueue.isEmpty() shouldBe true
            eventQueue.size shouldBe 0
            eventQueue.containsExternalEvent() shouldBe false
        }

        `when`("An internal event is offered"){
            val internalEvent = createInternalTestEvent()
            eventQueue.offer(event = internalEvent)

            then("it should contain the internal event but no external event"){
                eventQueue.isEmpty() shouldBe false
                eventQueue.size shouldBe 1
                eventQueue.containsExternalEvent() shouldBe false
                eventQueue.peek() shouldBe internalEvent
            }
        }

        `when`("An external event is offered"){
            val externalEvent = createExternalTestEvent()
            eventQueue.offer(event = externalEvent)

            then("it should mark hasExternalEvent as true"){
                eventQueue.containsExternalEvent() shouldBe true
                eventQueue.size shouldBe 2
            }
        }

        `when`("Poll is called"){
            val first = eventQueue.poll()

            then("the first event should be removed and size decreases"){
                first shouldNotBe null
                first?.name shouldBe INTERNAL_EVENT_NAME
                eventQueue.size shouldBe 1
            }

            and("containsExternalEvent should still be true"){
                eventQueue.containsExternalEvent() shouldBe true
            }

            and("polling again removes the external event resets has ExternalEvent"){
                val second = eventQueue.poll()
                second shouldNotBe null
                second?.name shouldBe EXTERNAL_EVENT_NAME
                eventQueue.size shouldBe 0
                eventQueue.containsExternalEvent() shouldBe false
            }
        }

        `when`("multiple events are offered"){
            val e1 = TestCommandEvent(name = "e1")
            val e2 = TestCommandEvent(name = "e2")
            val e3 = TestCommandEvent(name = "e3")

            eventQueue.offer(event=e1)
            eventQueue.offer(event=e2)
            eventQueue.offer(event=e3)

            then("snapshot returns all events in order"){
                eventQueue.snapshot().map { it.name } shouldBe listOf(e1.name, e2.name, e3.name)
            }
        }
    }
})