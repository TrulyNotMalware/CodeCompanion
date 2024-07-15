package dev.notypie.domain.command

import dev.notypie.domain.command.entity.CommandSet
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CommandSetTest: BehaviorSpec({

    val unknownCommandString = "I_AM_UNKNOWN_STRINGS"
    val notCapitalCommand = "NOtiCE"
    val validCommand = "NOTICE"

    given("CommandSet"){
        `when`("parse unknown command"){
            val result = CommandSet.parseCommand(stringCommand = unknownCommandString)
            then("return CommandSet.UNKNOWN fields"){
                result shouldBe CommandSet.UNKNOWN
            }
        }

        `when`("parse exists commands"){
            val result = CommandSet.parseCommand(stringCommand = notCapitalCommand)
            val validResult = CommandSet.parseCommand(stringCommand = validCommand)
            then("verify case insensitive behavior"){
                result shouldBe CommandSet.NOTICE
            }

            then("successfully parsed"){
                result shouldBe CommandSet.NOTICE
            }
        }

    }
})