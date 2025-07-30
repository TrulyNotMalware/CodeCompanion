package dev.notypie.repository.task

import dev.notypie.repository.task.schema.TaskSchema

class TaskRepositoryImpl(
    private val jpaTaskRepository: JpaTaskRepository
) : TaskRepository {

    override fun createNewTask(taskSchema: TaskSchema): TaskSchema = jpaTaskRepository.save(taskSchema)
}