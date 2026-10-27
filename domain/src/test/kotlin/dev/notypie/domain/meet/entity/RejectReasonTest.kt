package dev.notypie.domain.meet.entity

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RejectReasonTest :
    BehaviorSpec({
        given("RejectReason.PERSONAL_REASON") {
            then("it renders in sentence case like its siblings") {
                RejectReason.PERSONAL_REASON.showMessage shouldBe "Personal reason"
            }
        }
    })
