package dev.notypie.application.service.task

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.form.RequestTaskContextResult
import dev.notypie.domain.command.entity.slash.RequestTaskCommand
import dev.notypie.domain.common.event.EventPublisher
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.task.TaskRepository
import dev.notypie.repository.task.schema.newTask
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.util.MultiValueMap

@Service
class TaskServiceImpl(
    private val slackEventBuilder: SlackEventBuilder,
    private val eventPublisher: EventPublisher,
    private val retryService: RetryService,
    private val taskRepository: TaskRepository
) : TaskService {

    @Transactional
    override fun handleTask(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        slackCommandData: SlackCommandData
    ) {
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
        val command: Command = RequestTaskCommand(
            commandData = slackCommandData,
            idempotencyKey = idempotencyKey,
            slackEventBuilder = this.slackEventBuilder,
            eventPublisher = this.eventPublisher
        )
        command.handleEvent()
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun testCreateNewTask(result: RequestTaskContextResult) {
        val task = result.newTask()
        this.retryService.execute(
            action = { taskRepository.createNewTask(taskSchema = task) }
        )
    }

}