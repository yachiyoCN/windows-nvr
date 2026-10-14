package cn.homewatch.recorder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Best-effort, network-constrained maintenance of the existing encrypted login. */
public final class SessionRenewalJob extends JobService {
    private static final int JOB_ID = 0x524543;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int runGeneration;
    private volatile RecorderApi running;

    static void schedule(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
            scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, SessionRenewalJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.HOURS.toMillis(12), TimeUnit.HOURS.toMillis(1))
                    .setBackoffCriteria(TimeUnit.MINUTES.toMillis(30), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true).build());
        } catch (SecurityException | IllegalStateException | IllegalArgumentException ignored) {
            // Some phone systems restrict background scheduling. Foreground renewal
            // still works, and a best-effort maintenance job must never prevent login.
        }
    }

    static void cancel(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) scheduler.cancel(JOB_ID);
        } catch (SecurityException | IllegalStateException ignored) { }
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        int current = ++runGeneration;
        worker.execute(() -> {
            boolean retry = false;
            SessionStore store = new SessionStore(this);
            final RecorderModels.Session saved;
            final long revision;
            // Capture the grant and its logout/login revision as one snapshot.
            // A new login between separate reads must not have its rotated grant discarded.
            synchronized (SessionStore.class) { saved = store.load(); revision = store.revision(); }
            RecorderApi request = null;
            try {
                if (saved == null || current != runGeneration) return;
                request = new RecorderApi(saved, renewed -> store.update(renewed, revision));
                running = request;
                if (current != runGeneration) { request.close(); return; }
                request.renewRememberedSession();
            } catch (Exception error) {
                if (error instanceof RecorderApi.Failure && ((RecorderApi.Failure) error).status == 401) {
                    // A stopped worker cannot clear a newer login or cancel its job.
                    if (current == runGeneration && store.clearToken(revision)) cancel(this);
                } else retry = true; // Offline, TLS, or storage errors preserve the encrypted session.
            } finally {
                if (request != null) request.close();
                if (current == runGeneration) { running = null; jobFinished(parameters, retry); }
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        runGeneration++;
        RecorderApi request = running; running = null;
        if (request != null) request.close();
        return true;
    }

    @Override public void onDestroy() {
        runGeneration++;
        RecorderApi request = running; if (request != null) request.close();
        worker.shutdown(); super.onDestroy();
    }
}
