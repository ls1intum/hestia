UPDATE extraction_run
SET status = 'FAILED',
    error = COALESCE(
        error,
        failed_sessions || CASE
            WHEN failed_sessions = 1 THEN ' session could not be analysed. Retry the extraction.'
            ELSE ' sessions could not be analysed. Retry the extraction.'
        END
    )
WHERE status = 'SUCCEEDED'
  AND failed_sessions > 0;
