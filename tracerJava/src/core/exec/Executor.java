package core.exec;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import com.sun.tools.jdi.VirtualMachineManagerImpl;
import core.EventProcessor;
import core.Project;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes a compiled project.
 */
public class Executor {
    private Project project;
    private EventProcessor eventProcessor;
    private VirtualMachine vm;

    /**
     * Initializes the executor with the received project and the event processor, the project shall be compiled.
     */
    public Executor(Project project, EventProcessor eventProcessor) {
        this.project = project;
        this.eventProcessor = eventProcessor;
    }

    public boolean isRunning() {
        return vm != null;
    }

    public Project getProject() {
        return project;
    }

    public EventProcessor getEventProcessor() {
        return eventProcessor;
    }

    /**
     * Executes the received project and send filtered events to the event processor.
     */
    public void execute() throws IOException, IllegalConnectorArgumentsException, VMStartException, VMDisconnectedException, InterruptedException {
        startVirtualMachine();
        var allowedThreadsNames = configureEventRequests();

        var vmStdin = vm.process().getOutputStream();
        var vmStdout = vm.process().getInputStream();
        var vmStderr = vm.process().getErrorStream();

        while (true) {
            try {
                vm.resume();

                var eventSet = vm.eventQueue().remove();
                /*
                // input hook TODO check better strategies (not working well)
                var eventSet = vm.eventQueue().remove(100);
                while (eventSet == null) {
                    vmStdin.write((eventProcessor.inputHook() + "\n").getBytes());
                    vmStdin.flush();
                    eventSet = vm.eventQueue().remove(100);
                }
                */

                for (var event : eventSet) {

                    // interrupt not allowed threads
                    if (event instanceof ThreadStartEvent &&
                            !allowedThreadsNames.contains(((ThreadStartEvent) event).thread().name()))
                        ((ThreadStartEvent) event).thread().interrupt();


                    // print hooks
                    var printAvailable = vmStdout.available();
                    if (printAvailable > 0)
                        eventProcessor.printHook(new String(vmStdout.readNBytes(printAvailable)));
                    var errorAvailable = vmStderr.available();
                    if (errorAvailable > 0)
                        eventProcessor.printHook(new String(vmStderr.readNBytes(errorAvailable)));

                    // trace
                    var continueTracing = eventProcessor.trace(event);
                    if (!continueTracing) {
                        vm.exit(0);
                        // breaks only internal loop to stop event set iterator
                        // throws precipitated VMDisconnectedException without reading the rest of the events
                        break;
                    }
                }
            } catch (VMDisconnectedException e) {
                break;
            }
        }
    }

    /**
     * Returns a virtual machine reference running the project.
     */
    private void startVirtualMachine() throws IOException, IllegalConnectorArgumentsException, VMStartException {
        if (!project.isCompiled()) throw new IllegalStateException("project not compiled");
        if (isRunning()) throw new IllegalStateException("executor already running");

        var vmm = VirtualMachineManagerImpl.virtualMachineManager();
        var connector = vmm.defaultConnector();

        var connectorArguments = connector.defaultArguments();
        connectorArguments.get(ConnectorArguments.SUSPEND.arg).setValue("true");
        connectorArguments.get(ConnectorArguments.OPTIONS.arg).setValue(
                "-cp \"" + project.getBinPath().toAbsolutePath().toString() + "\""
        );
        connectorArguments.get(ConnectorArguments.MAIN.arg).setValue(
                project.getFilename().substring(0, project.getFilename().lastIndexOf('.'))
        );

        vm = connector.launch(connectorArguments);
    }

    /**
     * Sets all necessary requests for executing the project.
     */
    private Set<String> configureEventRequests() {
        var defaultThreads = List.copyOf(vm.allThreads());
        var mainThread = defaultThreads.stream()
                .filter(t -> t.name().equals("main"))
                .findFirst()
                .orElseThrow(() -> new NullPointerException("main thread not found"));
        var allowedThreadsNames = defaultThreads.stream()
                .map(ThreadReference::name)
                .collect(Collectors.toCollection(HashSet::new));
        allowedThreadsNames.addAll(Set.of("Common-Cleaner", "DestroyJavaVM"));

        var vmDeathRequest = vm.eventRequestManager().createVMDeathRequest();

        var threadStartRequest = vm.eventRequestManager().createThreadStartRequest();
        threadStartRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        var threadDeathRequest = vm.eventRequestManager().createThreadDeathRequest();
        threadDeathRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);

        var classNames = project.getClasses();

        var methodEntryRequests = classNames.stream()
                .map(c -> {
                    var methodEntryRequest = vm.eventRequestManager().createMethodEntryRequest();
                    methodEntryRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    methodEntryRequest.addThreadFilter(mainThread);
                    methodEntryRequest.addClassFilter(c);
                    return methodEntryRequest;
                })
                .collect(Collectors.toList());

        var methodExitRequests = classNames.stream()
                .map(c -> {
                    var methodExitRequest = vm.eventRequestManager().createMethodExitRequest();
                    methodExitRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    methodExitRequest.addThreadFilter(mainThread);
                    methodExitRequest.addClassFilter(c);
                    return methodExitRequest;
                })
                .collect(Collectors.toList());

        var stepRequests = classNames.stream()
                .map(c -> {
                    var stepRequest = vm.eventRequestManager()
                            .createStepRequest(mainThread, StepRequest.STEP_MIN, StepRequest.STEP_INTO);
                    stepRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    stepRequest.addClassFilter(c);
                    return stepRequest;
                })
                .collect(Collectors.toList());

        var exceptionRequests = classNames.stream()
                .map(c -> {
                    var exceptionRequest = vm.eventRequestManager()
                            .createExceptionRequest(null, true, true);
                    exceptionRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    exceptionRequest.addClassFilter(c);
                    return exceptionRequest;
                })
                .collect(Collectors.toList());
        // TODO implement step into try blocks

        vmDeathRequest.enable();
        threadStartRequest.enable();
        threadDeathRequest.enable();
        methodEntryRequests.forEach(EventRequest::enable);
        methodExitRequests.forEach(EventRequest::enable);
        stepRequests.forEach(EventRequest::enable);
        exceptionRequests.forEach(EventRequest::enable);

        return allowedThreadsNames;
    }
}
