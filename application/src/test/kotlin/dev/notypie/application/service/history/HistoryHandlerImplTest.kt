package dev.notypie.application.service.history

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.History
import dev.notypie.domain.history.entity.Status
import dev.notypie.domain.history.repository.HistoryRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class HistoryHandlerImplTest :
    BehaviorSpec({
        val historyRepository = mockk<HistoryRepository>()
        val handler = HistoryHandlerImpl(historyRepository = historyRepository)

        val testId = UUID.randomUUID()
        val testHistory =
            History(
                historyId = testId,
                publisherId = TEST_USER_ID,
                channel = TEST_CHANNEL_ID,
                status = Status.IN_PROGRESSED,
                apiAppId = TEST_APP_ID,
                commandType = CommandType.SIMPLE,
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
            )

        given("saveNewHistory") {
            `when`("called with a valid history") {
                every { historyRepository.insertNewHistory(history = testHistory) } returns testHistory

                handler.saveNewHistory(history = testHistory)

                then("delegates to historyRepository.insertNewHistory") {
                    verify(exactly = 1) { historyRepository.insertNewHistory(history = testHistory) }
                }
            }
        }

        given("getHistory") {
            `when`("called with a valid id") {
                every { historyRepository.getHistoryById(id = testId) } returns testHistory

                val result = handler.getHistory(id = testId)

                then("returns the history from repository") {
                    result shouldBe testHistory
                }

                then("delegates to historyRepository.getHistoryById") {
                    verify(exactly = 1) { historyRepository.getHistoryById(id = testId) }
                }
            }
        }
    })
