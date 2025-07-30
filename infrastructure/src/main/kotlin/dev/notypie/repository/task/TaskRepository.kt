package dev.notypie.repository.task

import dev.notypie.repository.task.schema.TaskSchema

interface TaskRepository {
    fun createNewTask(taskSchema: TaskSchema): TaskSchema
}