package azkaban.jobs;

import azkaban.app.JobFactory;
import azkaban.app.JavaJob;
import azkaban.app.JobManager;
import azkaban.app.ProcessJob;
import azkaban.app.Scheduler;
import azkaban.common.jobs.Job;
import azkaban.common.utils.Props;
import azkaban.common.utils.Utils;
import azkaban.flow.ExecutableFlow;
import azkaban.flow.JobManagerFlowDeserializer;
import azkaban.flow.manager.FlowManager;
import azkaban.flow.manager.RefreshableFlowManager;
import azkaban.jobcontrol.impl.jobs.locks.NamedPermitManager;
import azkaban.jobcontrol.impl.jobs.locks.ReadWriteLockManager;
import azkaban.serialization.DefaultExecutableFlowSerializer;
import azkaban.serialization.ExecutableFlowSerializer;
import azkaban.serialization.de.ExecutableFlowDeserializer;
import azkaban.serialization.de.JobFlowDeserializer;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.joda.time.DateTime;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static java.util.Arrays.asList;

/**
 * Runs a job from the command line
 * 
 * The usage is
 * 
 * java azkaban.job.CommandLineJobRunner props-file prop_key=prop_val
 * 
 * Any argument that contains an '=' is assumed to be a property, all others are
 * assumed to be properties files for the job
 * 
 * The order of the properties files matters--in the case where both define a
 * property it will be read from the last file given.
 * 
 * @author jkreps
 * 
 */
public class CommandLineJobRunner {

    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();
        OptionSpec<String> overrideOpt = parser.acceptsAll(asList("o", "override"),
                                                           "An override property to be used instead of what is in the job")
                                               .withRequiredArg()
                                               .describedAs("key=val");
        String ignoreDepsOpt = "ignore-deps";
        parser.accepts(ignoreDepsOpt, "Run only the specified job, ignoring dependencies");
        AzkabanCommandLine cl = new AzkabanCommandLine(parser, args);

        String helpMessage = "USAGE: bin/run-job.sh [options] job_name...";
        OptionSet options = cl.getOptions();
        if(cl.hasHelp())
            cl.printHelpAndExit(helpMessage, System.out);

        List<String> jobNames = options.nonOptionArguments();
        if(jobNames.size() < 1)
            cl.printHelpAndExit(helpMessage, System.err);
                
        // parse override properties
        boolean ignoreDeps = options.has(ignoreDepsOpt);
        Props overrides = new Props(null);
        for(String override: options.valuesOf(overrideOpt)) {
            String[] pieces = override.split("=");
            if(pieces.length != 2)
                Utils.croak("Invalid property override: '" + override
                            + "', properties must be in the form key=value", 1);
            overrides.put(pieces[0], pieces[1]);
        }

        NamedPermitManager permitManager = new NamedPermitManager();
        permitManager.createNamedPermit("default", cl.getNumWorkPermits());
        
        JobFactory factory = new JobFactory(
                permitManager,
                new ReadWriteLockManager(),
                cl.getLogDir().getAbsolutePath(),
                "java",
                ImmutableMap.<String, Class<? extends Job>>of("java", JavaJob.class,
                                                              "command", ProcessJob.class)
        );

        JobManager jobManager = new JobManager(factory,
                                               cl.getLogDir().getAbsolutePath(),
                                               cl.getDefaultProps(),
                                               cl.getJobDirs(),
                                               cl.getClassloader());

        File executionsStorageFile = new File(".");
        if (! executionsStorageFile.exists()) {
            executionsStorageFile.mkdirs();
        }

        long lastId = 0;
        for (File file : executionsStorageFile.listFiles()) {
            final String filename = file.getName();
            if (filename.endsWith(".json")) {
                try {
                    lastId = Math.max(
                            lastId,
                            Long.parseLong(filename.substring(0, filename.length() - 5))
                    );
                }
                catch (NumberFormatException e) {
                }
            }
        }

        final ExecutableFlowSerializer flowSerializer = new DefaultExecutableFlowSerializer();
        final ExecutableFlowDeserializer flowDeserializer = new ExecutableFlowDeserializer(
                new JobFlowDeserializer(
                        ImmutableMap.<String, Function<Map<String, Object>, ExecutableFlow>>of(
                                "jobManagerLoaded", new JobManagerFlowDeserializer(jobManager, factory)
                        )
                )
        );
        FlowManager allFlows = new RefreshableFlowManager(jobManager, factory, flowSerializer, flowDeserializer, executionsStorageFile, lastId);

        Scheduler scheduler = new Scheduler(jobManager,
                                            allFlows,
                                            null,
                                            null,
                                            null,
                                            cl.getClassloader(),
                                            null,
                                            null,
                                            3);

        List<ScheduledFuture<?>> jobCompletionFutures = new ArrayList<ScheduledFuture<?>>();
        for(String jobName: jobNames) {
            try {
                System.out.println("Running " + jobName);
                Job theJob = jobManager.loadJob(jobName, overrides, ignoreDeps);
                jobCompletionFutures.add(scheduler.schedule(theJob.getId(),
                                                            new DateTime(),
                                                            ignoreDeps));
            } catch(Exception e) {
                System.out.println("Failed to run job '" + jobName + "':");
                e.printStackTrace();
            }
        }

        // wait for jobs to finish
        for(ScheduledFuture<?> future: jobCompletionFutures)
            future.get();
    }

}