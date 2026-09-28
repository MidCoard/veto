package top.focess.veto.builtin;

import org.jspecify.annotations.NonNull;

import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.builtin.group.GroupRuntime;
import top.focess.veto.builtin.memory.MemoryRuntime;
import top.focess.veto.builtin.monitor.MonitorRuntime;
import top.focess.veto.builtin.process.ProcessRuntime;
import top.focess.veto.builtin.process.TaskEvents;
import top.focess.veto.builtin.questions.QuestionRuntime;
import top.focess.veto.builtin.search.BraveSearchProvider;
import top.focess.veto.builtin.skills.SkillRuntime;

import java.util.ArrayList;
import java.util.List;

/** Owns the builtin plugin's host-bound components as one initialized lifecycle unit. */
final class BuiltinComponents implements AutoCloseable {
    final @NonNull PluginHost host;
    final @NonNull QuestionRuntime questions;
    final @NonNull GroupRuntime groups;
    final @NonNull MonitorRuntime monitors;
    final @NonNull ProcessRuntime processes;
    final @NonNull TaskEvents taskEvents;
    final @NonNull MemoryRuntime memory;
    final @NonNull SkillRuntime skills;
    final @NonNull BraveSearchProvider brave;

    /** Constructs all components or releases those already constructed on failure. */
    BuiltinComponents(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            host = context.host();
            questions = new QuestionRuntime(host);
            cleanup.add(questions::close);
            groups = new GroupRuntime(context, configuration);
            cleanup.add(groups::close);
            groups.awaitHostReady();
            monitors = new MonitorRuntime(context, groups);
            cleanup.add(monitors::close);
            taskEvents = new TaskEvents(host, monitors.service());
            cleanup.add(taskEvents::close);
            processes = new ProcessRuntime(context, taskEvents);
            cleanup.add(() -> processes.tasks().close());
            memory = new MemoryRuntime(context, configuration);
            skills = new SkillRuntime(context, configuration);
            cleanup.add(skills::close);
            var key = configuration.values().get("brave-api-key");
            brave =
                    new BraveSearchProvider(
                            key instanceof JsonValue.StringValue value ? value.value() : "");
            cleanup.add(brave::close);
        } catch (RuntimeException failure) {
            for (int index = cleanup.size() - 1; index >= 0; index--) {
                try {
                    cleanup.get(index).run();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    /** Stops waits before plugin admission drains. */
    void stopping() {
        closeAll(List.of(questions::close, () -> processes.tasks().close()));
    }

    /** Closes all owned components after plugin admission drains. */
    @Override
    public void close() {
        closeAll(
                List.of(
                        () -> processes.tasks().close(),
                        skills::close,
                        taskEvents::close,
                        questions::close,
                        monitors::close,
                        groups::close,
                        brave::close));
    }

    private static void closeAll(@NonNull List<Runnable> closers) {
        RuntimeException failure = null;
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
