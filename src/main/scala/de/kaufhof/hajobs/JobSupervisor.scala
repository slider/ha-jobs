package de.kaufhof.hajobs

import java.util.UUID

import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Checks if for jobs in state RUNNING there's a lock as well. If this is not the case,
 * the job is considered to be crashed (because a job must regularly update the corresponding
 * lock).
 */
class JobSupervisor(jobManager: => JobManager,
                    jobUpdater: JobUpdater,
                    jobStatusRepository: JobStatusRepository,
                    cronExpression: Option[String]) extends Job(JobTypes.JobSupervisor, 0, cronExpression) {

  def this(jobManager: => JobManager,
           lockRepository: LockRepository,
           jobStatusRepository: JobStatusRepository,
           cronExpression: Option[String] = None) = this(jobManager, new JobUpdater(lockRepository, jobStatusRepository), jobStatusRepository, cronExpression)

  @volatile
  private var isCancelled = false

  /**
   * Starts dead job detection. The start status is returned as soon as we know if we have
   * the lock or not (actual job detection is running in the background after that).
   */
  def run()(implicit jobContext: JobContext): Future[JobStartStatus] = {
    isCancelled = false
    val jobId = jobContext.jobId
    Logger.info("Starting dead job detection...")

    val updatedJobs = jobUpdater.updateJobs()

    updatedJobs.onComplete {
      case Success(jobs) =>
        if(jobs.isEmpty) {
          Logger.info("Finished dead job detection, no dead jobs found.")
        } else {
          Logger.info(s"Dead job detection finished, changed jobs state to DEAD for ${jobs.length} jobs.")
        }
      case Failure(e) =>
        Logger.error("Error during dead job detection.", e)
    }

    updatedJobs.onComplete { res =>
      retriggerJobs().onComplete {
        case Success(retriggeredJobStatus) =>
          if (retriggeredJobStatus.isEmpty) {
            Logger.info("Finished retriggering jobs, no jobs to retrigger found.")
          } else {
            Logger.info(s"Retriggering jobs finished, retriggered ${retriggeredJobStatus.length} jobs.")
          }
          jobContext.finishCallback()
        case Failure(e) =>
          Logger.error("Error during dead job detection.", e)
          jobContext.finishCallback()
      }
    }

    Future.successful(Started(jobId))
  }

  override def cancel(): Unit = isCancelled = true

  /**
   * Retrigger all failed jobs. A job is considered failed if for the latest trigger id there is no succeded job
   * and no job is running. Failed job can only be retriggered a limited number of times.
   * @return
   */
  private[hajobs] def retriggerJobs(): Future[Seq[JobStartStatus]] = {
    if (!isCancelled) {
      jobStatusRepository.getAllMetadata().flatMap { allJobs =>
        val a = allJobs.groupBy(_.jobType).flatMap { case (jobType, jobStatus) =>
          triggerIdToRetrigger(jobType, jobStatus).map { triggerId =>
            Logger.info(s"retriggering job of type $jobType with triggerid $triggerId")
            jobManager.retriggerJob(jobType, triggerId)
          }
        }.toSeq
        Future.sequence(a)
      }
    } else {
      Future.successful(Nil)
    }
  }

  private def triggerIdToRetrigger(jobType: JobType, jobStatus: List[JobStatus]): Option[UUID] = {
    val job = jobManager.getJob(jobType)
    val latestTriggerId = jobStatus.sortBy(_.jobStatusTs.getMillis).last.triggerId
    val jobsOfLatestTrigger = jobStatus.filter(_.triggerId == latestTriggerId)

    // we don't need to restart a job that already succeeded
    // or thats pending, cause we don't know the reuslt of that job
    val someJobIsRunningOrPending = jobsOfLatestTrigger.exists(js => js.jobResult == JobResult.Pending
      || js.jobResult == JobResult.Success)

    if (!someJobIsRunningOrPending && jobsOfLatestTrigger.size <= job.retriggerCount) {
      Some(latestTriggerId)
    } else {
      None
    }
  }
}
