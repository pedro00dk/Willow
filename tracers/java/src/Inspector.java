import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.jdi.*;
import com.sun.jdi.event.*;

import java.util.*;
import java.util.stream.Collectors;

class InspectionResult {
    JsonObject snapshot;
    JsonObject threw;

    InspectionResult(JsonObject snapshot) {
        this(snapshot, null);
    }

    InspectionResult(JsonObject snapshot, JsonObject threw) {
        this.snapshot = snapshot;
        this.threw = threw;
    }
}

/**
 * Inspects the received event, building a snapshot from it.
 */
class Inspector {

    InspectionResult inspect(Event event, Event previousEvent, JsonObject previousSnapshot) throws IncompatibleThreadStateException {
        var snapshot = new JsonObject();
        snapshot.addProperty(
                "type",
                event instanceof MethodEntryEvent
                        ? "call"
                        : event instanceof MethodExitEvent || event instanceof ThreadDeathEvent
                        ? "return"
                        : event instanceof ExceptionEvent
                        ? "exception"
                        : "line"

        );

        if (event instanceof ThreadDeathEvent && previousSnapshot.get("type").getAsString().equals("exception")) {
            snapshot.add("stack", previousSnapshot.get("stack"));
            snapshot.add("heap", previousSnapshot.get("stack"));

            var exceptionEvent = (ExceptionEvent) previousEvent;
            var exceptionMessage = ((StringReference) exceptionEvent
                    .exception()
                    .getValue(exceptionEvent.exception().referenceType().fieldByName("detailMessage"))
            ).value();

            var threw = new JsonObject();
            threw.add("exception", DumpException.dump(exceptionEvent.exception().referenceType().name()));

            return new InspectionResult(snapshot, threw);
        }

        // Get frames may fail when debugging this debugger
        // frames() function returns an immutable list with the scopes in a reversed order
        var frames = new ArrayList<>(((LocatableEvent) event).thread().frames());
        Collections.reverse(frames);
        snapshot.add(
                "stack",
                frames.stream()
                        .map(StackFrame::location)
                        .map(l -> {
                            var scope = new JsonObject();
                            scope.addProperty("line", l.lineNumber() - 1);
                            scope.addProperty("name", l.method().name());
                            return scope;
                        })
                        .collect(
                                () -> new JsonArray(frames.size()),
                                JsonArray::add,
                                (scope0, scope1) -> {
                                    throw new RuntimeException("parallel stream not allowed");
                                }
                        )
        );

        snapshot.add("heap", new JsonObject());
        var threadReference = frames.get(0).thread();
        int[] scopeIndex = {0};
        frames.stream()
                .map(f -> {
                    try {
                        return Map.entry(f.visibleVariables(), f.getValues(f.visibleVariables()));
                    } catch (AbsentInformationException e) {
                        throw new RuntimeException(e);
                    }
                })
                // frames get invalid after invoking methods in objects
                // because of this, all variables have to be collected before any evaluation
                .collect(Collectors.toList())
                .forEach(e -> {
                    var localVariables = e.getKey();
                    var values = e.getValue();
                    var scope = snapshot.get("stack").getAsJsonArray().get(scopeIndex[0]++).getAsJsonObject();
                    scope.add(
                            "variables",
                            localVariables.stream()
                                    .map(localVariable -> {
                                        var variable = new JsonObject();
                                        variable.addProperty("name", localVariable.name());
                                        variable.add("value", inspectValue(snapshot, values.get(localVariable), threadReference));
                                        return variable;
                                    })
                                    .collect(
                                            () -> new JsonArray(localVariables.size()),
                                            JsonArray::add,
                                            (variable0, variable1) -> {
                                                throw new RuntimeException("parallel stream not allowed");
                                            }
                                    )
                    );
                });

        return new InspectionResult(snapshot);
    }

    private JsonElement inspectValue(JsonObject snapshot, Value jdiValue, ThreadReference threadReference) {
        JsonElement value;
        return jdiValue == null
                ? new JsonPrimitive("null")
                : (value = inspectPrimitive(jdiValue)) != null
                ? value
                : (value = inspectObject(snapshot, jdiValue, threadReference)) != null
                ? value
                : new JsonPrimitive("unknown");
    }

    private JsonElement inspectPrimitive(Value jdiValue) {
        return jdiValue instanceof PrimitiveValue ?
                jdiValue instanceof BooleanValue
                        ? new JsonPrimitive(Boolean.toString(((BooleanValue) jdiValue).value()))
                        : jdiValue instanceof CharValue
                        ? new JsonPrimitive(Character.toString(((CharValue) jdiValue).value()))
                        : jdiValue instanceof ByteValue
                        ? new JsonPrimitive(((ByteValue) jdiValue).value())
                        : jdiValue instanceof ShortValue
                        ? new JsonPrimitive(((ShortValue) jdiValue).value())
                        : jdiValue instanceof IntegerValue
                        ? new JsonPrimitive(((IntegerValue) jdiValue).value())
                        : jdiValue instanceof FloatValue
                        ? new JsonPrimitive(((FloatValue) jdiValue).value())
                        : jdiValue instanceof DoubleValue
                        ? new JsonPrimitive(((DoubleValue) jdiValue).value())
                        : jdiValue instanceof LongValue
                        ? Math.abs(((LongValue) jdiValue).value()) < Math.pow(2, 53)
                        ? new JsonPrimitive(((LongValue) jdiValue).value())
                        : new JsonPrimitive(Long.toString(((LongValue) jdiValue).value()))
                        : null
                : null;
    }

    private JsonElement inspectObject(JsonObject snapshot, Value jdiValue, ThreadReference threadReference) {
        JsonElement value;
        if (!(jdiValue instanceof ObjectReference)) return null;

        var jdiObjRef = (ObjectReference) jdiValue;
        return (value = inspectString(jdiObjRef)) != null
                ? value
                : (value = inspectBoxed(jdiObjRef)) != null
                ? value
                : (value = inspectArray(snapshot, jdiObjRef, threadReference)) != null
                ? value
                : (value = inspectCollection(snapshot, jdiObjRef, threadReference)) != null
                ? value
                : (value = inspectMap(snapshot, jdiObjRef, threadReference)) != null
                ? value
                : (value = inspectUserObject(snapshot, jdiObjRef, threadReference)) != null
                ? value
                : new JsonPrimitive("type " + jdiObjRef.referenceType().name());
    }

    private JsonElement inspectString(ObjectReference jdiObjRef) {
        return jdiObjRef instanceof StringReference ? new JsonPrimitive(((StringReference) jdiObjRef).value()) : null;
    }

    private JsonElement inspectBoxed(ObjectReference jdiObjRef) {
        try {
            var jdiObjRefClass = Class.forName(jdiObjRef.referenceType().name());
            return Boolean.class.isAssignableFrom(jdiObjRefClass) || Character.class.isAssignableFrom(jdiObjRefClass) ||
                    Byte.class.isAssignableFrom(jdiObjRefClass) || Short.class.isAssignableFrom(jdiObjRefClass) ||
                    Integer.class.isAssignableFrom(jdiObjRefClass) || Long.class.isAssignableFrom(jdiObjRefClass) ||
                    Float.class.isAssignableFrom(jdiObjRefClass) || Double.class.isAssignableFrom(jdiObjRefClass)
                    ? inspectPrimitive(jdiObjRef.getValue(jdiObjRef.referenceType().fieldByName("value")))
                    : null;
        } catch (ClassNotFoundException e) {
            // array types always throw this exception
        }
        return null;
    }

    private JsonElement inspectIterable(JsonObject snapshot, ArrayReference jdiArrRef, String reference, String type, String languageType, ThreadReference threadReference) {
        var obj = new JsonObject();
        // add reference to heap graph (it has to be added before other objects inspections)
        snapshot.get("heap").getAsJsonObject().add(reference, obj);
        obj.addProperty("type", type);
        obj.addProperty("languageType", languageType);
        obj.addProperty("userDefined", false);
        int[] memberIndex = {0};
        obj.add(
                "members",
                jdiArrRef.getValues().stream()
                        .map(jdiValue -> {
                            var member = new JsonObject();
                            member.addProperty("key", memberIndex[0]++);
                            member.add("value", inspectValue(snapshot, jdiValue, threadReference));
                            return member;
                        })
                        .collect(
                                () -> new JsonArray(jdiArrRef.length()),
                                JsonArray::add,
                                (member0, member1) -> {
                                    throw new RuntimeException("parallel stream not allowed");
                                }
                        )
        );
        var referenceValue = new JsonArray(1);
        referenceValue.add(reference);
        return referenceValue;
    }

    private JsonElement inspectArray(JsonObject snapshot, ObjectReference jdiObjRef, ThreadReference threadReference) {
        return jdiObjRef instanceof ArrayReference
                ? inspectIterable(
                snapshot,
                (ArrayReference) jdiObjRef,
                Long.toString(jdiObjRef.uniqueID()),
                "array",
                jdiObjRef.referenceType().name(),
                threadReference
        )
                : null;
    }

    private JsonElement inspectCollection(JsonObject snapshot, ObjectReference jdiObjRef, ThreadReference threadReference) {
        try {
            var jdiObjRefClass = Class.forName(jdiObjRef.referenceType().name());
            return Collection.class.isAssignableFrom(jdiObjRefClass)
                    ? inspectIterable(
                    snapshot,
                    (ArrayReference) jdiObjRef.invokeMethod(
                            threadReference,
                            jdiObjRef.referenceType().methodsByName("toArray", "()[Ljava/lang/Object;").get(0),
                            List.of(),
                            ObjectReference.INVOKE_SINGLE_THREADED
                    ),
                    Long.toString(jdiObjRef.uniqueID()),
                    LinkedList.class.isAssignableFrom(jdiObjRefClass)
                            ? "llist"
                            : List.class.isAssignableFrom(jdiObjRefClass)
                            ? "alist"
                            : "set",
                    jdiObjRef.referenceType().name(),
                    threadReference
            )
                    : null;
        } catch (ClassNotFoundException | InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e) {
            // ignore errors
        }
        return null;
    }

    private JsonElement inspectMap(JsonObject snapshot, ObjectReference jdiObjRef, ThreadReference threadReference) {
        try {
            var jdiObjRefClass = Class.forName(jdiObjRef.referenceType().name());
            if (!Map.class.isAssignableFrom(jdiObjRefClass)) return null;

            var jdiSetRef = (ObjectReference) jdiObjRef.invokeMethod(
                    threadReference,
                    jdiObjRef.referenceType().methodsByName("entrySet").get(0),
                    List.of(),
                    ObjectReference.INVOKE_SINGLE_THREADED
            );
            var jdiArrRef = (ArrayReference) jdiSetRef.invokeMethod(
                    threadReference,
                    jdiSetRef.referenceType().methodsByName("toArray", "()[Ljava/lang/Object;").get(0),
                    List.of(),
                    ObjectReference.INVOKE_SINGLE_THREADED
            );
            var reference = Long.toString(jdiObjRef.uniqueID());
            var obj = new JsonObject();
            // add reference to heap graph (it has to be added before other objects inspections)
            snapshot.get("heap").getAsJsonObject().add(reference, obj);
            obj.addProperty("type", "map");
            obj.addProperty("languageType", jdiObjRef.referenceType().name());
            obj.addProperty("userDefined", false);
            obj.add(
                    "members",
                    jdiArrRef.getValues().stream()
                            .map(jdiValue -> {
                                try {
                                    var jdiValueEntry = (ObjectReference) jdiValue;
                                    var jdiValueEntryKey = jdiValueEntry.invokeMethod(
                                            threadReference,
                                            jdiValueEntry.referenceType().methodsByName("getKey").get(0),
                                            List.of(),
                                            ObjectReference.INVOKE_SINGLE_THREADED
                                    );
                                    var jdiValueEntryValue = jdiValueEntry.invokeMethod(
                                            threadReference,
                                            jdiValueEntry.referenceType().methodsByName("getValue").get(0),
                                            List.of(),
                                            ObjectReference.INVOKE_SINGLE_THREADED
                                    );
                                    var member = new JsonObject();
                                    member.add("key", inspectValue(snapshot, jdiValueEntryKey, threadReference));
                                    member.add("value", inspectValue(snapshot, jdiValueEntryValue, threadReference));
                                    return member;
                                } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .collect(
                                    () -> new JsonArray(jdiArrRef.length()),
                                    JsonArray::add,
                                    (member0, member1) -> {
                                        throw new RuntimeException("parallel stream not allowed");
                                    }
                            )
            );
            var referenceValue = new JsonArray(1);
            referenceValue.add(reference);
            return referenceValue;
        } catch (ClassNotFoundException | InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e) {
            // ignore errors
        }
        return null;
    }

    private JsonElement inspectUserObject(JsonObject snapshot, ObjectReference jdiObjRef, ThreadReference threadReference) {
        var jdiObjClassRefData = jdiObjRef.referenceType().toString().split(" ");

        // check if is a class and is defined in the default package (user defined)
        if (!jdiObjClassRefData[0].equals("class") || jdiObjClassRefData[1].contains(".")) return null;

        var orderedFields = jdiObjRef.referenceType().allFields();
        var fieldsValues = jdiObjRef.getValues(orderedFields);
        var reference = Long.toString(jdiObjRef.uniqueID());
        var obj = new JsonObject();
        // add reference to heap graph (it has to be added before other objects inspections)
        snapshot.get("heap").getAsJsonObject().add(reference, obj);
        obj.addProperty("type", "map");
        obj.addProperty("languageType", jdiObjRef.referenceType().name());
        obj.addProperty("userDefined", true);
        obj.add(
                "members",
                orderedFields.stream()
                        .map(field -> {
                            var member = new JsonObject();
                            member.addProperty("key", field.name());
                            member.add("value", inspectValue(snapshot, fieldsValues.get(field), threadReference));
                            return member;
                        })
                        .collect(
                                () -> new JsonArray(orderedFields.size()),
                                JsonArray::add,
                                (member0, member1) -> {
                                    throw new RuntimeException("parallel stream not allowed");
                                }
                        )
        );
        var referenceValue = new JsonArray(1);
        referenceValue.add(reference);
        return referenceValue;
    }
}

