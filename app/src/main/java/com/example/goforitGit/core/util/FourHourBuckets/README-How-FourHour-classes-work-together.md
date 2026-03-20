# GoForIt — FourHour class explanation

This README explains **how FourHour classes work together** in the GoForIt project.

## How they work together (the pipeline)

Worker    is FourHourUploadWorker
Scheduler is FourHourUploadScheduler

- `All day: StepService keeps calling FourHourBucketsSinceBoot.update(sensorSteps)`

- `At boundary (transition between slots): Scheduler runs Worker (e.g., 12:05)`

- `Worker reads the finished slot total and uploads it`

- `Worker marks it uploaded and asks Scheduler to schedule the next boundary`

- `At midnight: All of the buckets reset to a new dayKey, but yesterday slot 5 can still be uploaded at ~00:05 using the snapshot/dayKey logic.`
