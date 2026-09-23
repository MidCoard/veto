package top.focess.veto.api.group;

import java.time.*;
import java.util.*;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.*;

public record Inspection(
        @NonNull GroupSnapshot group, @NonNull List<@NonNull BlackboardMessage> messages) {}
