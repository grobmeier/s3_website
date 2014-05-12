package s3

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.{TimeUnit, Duration}
import s3.website.Utils._
import s3.website.S3.{PushSuccessReport, PushFailureReport}
import com.amazonaws.AmazonServiceException
import s3.website.model.{Config, Site}

package object website {
  trait Report {
    def reportMessage: String
  }
  trait SuccessReport extends Report

  trait FailureReport extends Report

  trait ErrorReport extends Report

  trait RetrySettings {
    def retryTimeUnit: TimeUnit
  }

  type PushErrorOrSuccess = Either[PushFailureReport, PushSuccessReport]

  type Attempt = Int

  def retry[L <: Report, R](attempt: Attempt)
                           (createFailureReport: (Throwable) => L, retryAction: (Attempt) => Future[Either[L, R]])
                           (implicit retrySettings: RetrySettings, ec: ExecutionContextExecutor, logger: Logger):
  PartialFunction[Throwable, Future[Either[L, R]]] = {
    case error: Throwable if attempt == 6 || isIrrecoverable(error) =>
      val failureReport = createFailureReport(error)
      logger.fail(failureReport.reportMessage)
      Future(Left(failureReport))
    case error: Throwable =>
      val failureReport = createFailureReport(error)
      val sleepDuration = Duration(fibs.drop(attempt + 1).head, retrySettings.retryTimeUnit)
      logger.pending(s"${failureReport.reportMessage}. Trying again in $sleepDuration.")
      Thread.sleep(sleepDuration.toMillis)
      retryAction(attempt + 1)
  }

  def isIrrecoverable(error: Throwable) = {
    val httpStatusCode =
      error match {
        case exception: AmazonServiceException => Some(exception.getStatusCode)
        case _ => None
      }
    httpStatusCode.exists(c => c >= 400 && c < 500)
  }
  
  implicit class NumReport(val num: Int) extends AnyVal {
    def ofType(itemType: String) = countToString(num, itemType)

    private def countToString(count: Int, singular: String) = {
      def plural = s"${singular}s"
      s"$count ${if (count > 1) plural else singular}"
    }
  }

  implicit def site2Config(implicit site: Site): Config = site.config
}
