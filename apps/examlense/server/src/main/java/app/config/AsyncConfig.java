package app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * Background pool for solve-exam orchestration. Sized small on purpose —
     * each task spends most of its time waiting on the AI gateway, so adding
     * threads doesn't help. Throttling there is the real bottleneck.
     */
    @Bean(name = "solverExecutor")
    public Executor solverExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(6);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("solver-");
        exec.initialize();
        return exec;
    }

    /**
     * Background pool for cropping figures out of a parsed PDF. Deliberately not
     * the solver pool: that one is sized for threads idling on the AI gateway,
     * whereas page rendering is CPU- and heap-bound (an A4 page at 200 DPI is
     * ~15 MB), so running six of these at once is how you get an OOM.
     */
    @Bean(name = "figureExecutor")
    public Executor figureExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(20);
        exec.setThreadNamePrefix("figure-");
        exec.initialize();
        return exec;
    }

    /**
     * Background pool for LearningGoalHub goal generation, separate from the
     * solver pool so a slow LGH run (one LLM call per task, synchronous on
     * their side) can never starve solve-exam orchestration.
     */
    @Bean(name = "lghExecutor")
    public Executor lghExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("lgh-");
        exec.initialize();
        return exec;
    }
}
