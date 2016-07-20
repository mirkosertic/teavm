/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.llvm;

import static org.teavm.llvm.LLVMRenderingHelper.defaultValue;
import static org.teavm.llvm.LLVMRenderingHelper.getJavaTypeName;
import static org.teavm.llvm.LLVMRenderingHelper.mangleField;
import static org.teavm.llvm.LLVMRenderingHelper.mangleMethod;
import static org.teavm.llvm.LLVMRenderingHelper.methodType;
import static org.teavm.llvm.LLVMRenderingHelper.renderItemType;
import static org.teavm.llvm.LLVMRenderingHelper.renderType;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntObjectOpenHashMap;
import com.carrotsearch.hppc.IntOpenHashSet;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.teavm.common.Graph;
import org.teavm.common.GraphUtils;
import org.teavm.llvm.context.CallSite;
import org.teavm.llvm.context.LLVMCallSiteRegistry;
import org.teavm.llvm.context.LayoutProvider;
import org.teavm.llvm.context.StringPool;
import org.teavm.llvm.context.TagRegistry;
import org.teavm.llvm.context.VirtualTableEntry;
import org.teavm.llvm.context.VirtualTableProvider;
import org.teavm.model.BasicBlock;
import org.teavm.model.BasicBlockReader;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReference;
import org.teavm.model.IncomingReader;
import org.teavm.model.Instruction;
import org.teavm.model.InstructionLocation;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHandle;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.Phi;
import org.teavm.model.PhiReader;
import org.teavm.model.Program;
import org.teavm.model.RuntimeConstant;
import org.teavm.model.TryCatchBlock;
import org.teavm.model.TryCatchBlockReader;
import org.teavm.model.TryCatchJoint;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.VariableReader;
import org.teavm.model.instructions.ArrayElementType;
import org.teavm.model.instructions.BinaryBranchingCondition;
import org.teavm.model.instructions.BinaryOperation;
import org.teavm.model.instructions.BranchingCondition;
import org.teavm.model.instructions.CastIntegerDirection;
import org.teavm.model.instructions.CloneArrayInstruction;
import org.teavm.model.instructions.ConstructArrayInstruction;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.InitClassInstruction;
import org.teavm.model.instructions.InstructionReader;
import org.teavm.model.instructions.IntegerSubtype;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.NumericOperandType;
import org.teavm.model.instructions.RaiseInstruction;
import org.teavm.model.instructions.SwitchTableEntryReader;
import org.teavm.model.util.DefinitionExtractor;
import org.teavm.model.util.LivenessAnalyzer;
import org.teavm.model.util.ProgramUtils;
import org.teavm.model.util.TypeInferer;
import org.teavm.model.util.VariableType;

class LLVMMethodRenderer {
    private Appendable appendable;
    private ClassReaderSource classSource;
    private StringPool stringPool;
    private LayoutProvider layoutProvider;
    private VirtualTableProvider vtableProvider;
    private TagRegistry tagRegistry;
    private LLVMCallSiteRegistry callSiteRegistry;
    private List<String> emitted = new ArrayList<>();
    private int temporaryVariable;
    private TypeInferer typeInferer;
    private Map<Variable, List<Variable>> joints;
    private BasicBlock currentBlock;
    private BitSet callSiteLiveIns;
    private int stackFrameSize;
    private List<String> returnVariables = new ArrayList<>();
    private List<String> returnBlocks = new ArrayList<>();
    private int lastCallSiteId;
    private boolean errorBlockNeeded;
    private boolean exceptionThrown;
    private Program program;
    private Graph cfg;
    private int[] firstJointValues;
    private Map<Variable, Variable> currentJointValues = new HashMap<>();
    private Map<Variable, List<String>> jointPhiIncomings = new HashMap<>();
    private List<IntSet> exceptionHandlerTransitions = new ArrayList<>();
    private List<IntSet> exceptionHandlerBackTransitions = new ArrayList<>();

    LLVMMethodRenderer(Appendable appendable, ClassReaderSource classSource,
            StringPool stringPool, LayoutProvider layoutProvider,
            VirtualTableProvider vtableProvider, TagRegistry tagRegistry,
            LLVMCallSiteRegistry callSiteRegistry) {
        this.appendable = appendable;
        this.classSource = classSource;
        this.stringPool = stringPool;
        this.layoutProvider = layoutProvider;
        this.vtableProvider = vtableProvider;
        this.tagRegistry = tagRegistry;
        this.callSiteRegistry = callSiteRegistry;
    }

    public void renderMethod(MethodReader method) throws IOException {
        if (method.hasModifier(ElementModifier.NATIVE) || method.hasModifier(ElementModifier.ABSTRACT)) {
            return;
        }

        appendable.append("define ").append(renderType(method.getResultType())).append(" ");
        appendable.append("@").append(mangleMethod(method.getReference())).append("(");
        List<String> parameters = new ArrayList<>();
        if (!method.hasModifier(ElementModifier.STATIC)) {
            parameters.add("i8* %v0");
        }
        for (int i = 0; i < method.parameterCount(); ++i) {
            String type = renderType(method.parameterType(i));
            parameters.add(type + " %v" + (i + 1));
        }
        appendable.append(parameters.stream().collect(Collectors.joining(", "))).append(") {\n");

        errorBlockNeeded = false;
        if (method.getProgram() != null && method.getProgram().basicBlockCount() > 0) {
            DefinitionExtractor defExtractor = new DefinitionExtractor();
            program = ProgramUtils.copy(method.getProgram());
            cfg = ProgramUtils.buildControlFlowGraph(program);
            findExceptionHandlerTransitions(program);

            typeInferer = new TypeInferer();
            typeInferer.inferTypes(program, method.getReference());

            List<IntObjectMap<BitSet>> callSiteLiveIns = findCallSiteLiveIns(method);
            stackFrameSize = getStackFrameSize(callSiteLiveIns);
            returnBlocks.clear();
            returnVariables.clear();

            if (method.hasModifier(ElementModifier.STATIC) && !method.getName().equals("<clinit>")
                    || method.getName().equals("<init>")) {
                appendable.append("    call void @initializer$" + method.getOwnerName() + "()\n");
            }

            String stackType = "{ %teavm.stackFrame, [" + stackFrameSize + " x i8*] }";
            appendable.append("    %stack = alloca " + stackType + "\n");
            appendable.append("    %stackHeader = getelementptr " + stackType + ", " + stackType + "* %stack, "
                    + "i32 0, i32 0\n");
            appendable.append("    %stackNext = getelementptr %teavm.stackFrame, "
                    + "%teavm.stackFrame* %stackHeader, i32 0, i32 2\n");
            appendable.append("    %stackTop = load %teavm.stackFrame*, %teavm.stackFrame** @teavm.stackTop\n");
            appendable.append("    store %teavm.stackFrame* %stackTop, %teavm.stackFrame** %stackNext\n");
            appendable.append("    store %teavm.stackFrame* %stackHeader, %teavm.stackFrame** @teavm.stackTop\n");
            appendable.append("    %sizePtr = getelementptr %teavm.stackFrame, %teavm.stackFrame* %stackHeader, "
                    + "i32 0, i32 0\n");
            appendable.append("    store i32 " + stackFrameSize + ", i32* %sizePtr\n");
            appendable.append("    %stackData = getelementptr " + stackType + ", " + stackType + "* %stack, "
                    + "i32 0, i32 1\n");
            appendable.append("    %callSiteIdRef = getelementptr %teavm.stackFrame, %teavm.stackFrame* %stackHeader, "
                    + "i32 0, i32 1\n");

            appendable.append("    br label %b0\n");

            temporaryVariable = 0;
            firstJointValues = getFirstJointValues(method);

            for (int i = 0; i < program.basicBlockCount(); ++i) {
                IntObjectMap<BitSet> blockLiveIns = callSiteLiveIns.get(i);
                BasicBlock block = program.basicBlockAt(i);
                appendable.append("b" + block.getIndex() + ":\n");

                joints = new HashMap<>();
                currentJointValues.clear();
                jointPhiIncomings.clear();

                for (TryCatchBlock tryCatch : block.getTryCatchBlocks()) {
                    for (TryCatchJoint joint : tryCatch.getJoints()) {
                        for (Variable jointSource : joint.getSourceVariables()) {
                            joints.computeIfAbsent(jointSource, x -> new ArrayList<>()).add(joint.getReceiver());
                        }
                        int receiver = joint.getReceiver().getIndex();
                        currentJointValues.put(joint.getReceiver(), program.variableAt(firstJointValues[receiver]));
                        jointPhiIncomings.put(joint.getReceiver(), new ArrayList<>());
                    }
                }

                for (PhiReader phi : block.readPhis()) {
                    String type = renderType(typeInferer.typeOf(phi.getReceiver().getIndex()));
                    appendable.append("    %v" + phi.getReceiver().getIndex() + " = phi " + type);
                    boolean first = true;
                    for (IncomingReader incoming : phi.readIncomings()) {
                        if (!first) {
                            appendable.append(", ");
                        }
                        first = false;
                        boolean fromException = exceptionHandlerBackTransitions.get(block.getIndex())
                                .contains(incoming.getSource().getIndex());
                        String fromBlock = fromException
                                ? "%b" + incoming.getSource().getIndex() + ".catch.to." + block.getIndex()
                                : "%b" + incoming.getSource().getIndex() + ".exit";
                        appendable.append(" [ %v" + incoming.getValue().getIndex() + ", " + fromBlock + " ]");
                    }
                    appendable.append("\n");
                }

                if (block.getExceptionVariable() != null) {
                    String exception = "%v" + block.getExceptionVariable().getIndex();
                    emitted.add(exception + " = call i8* @teavm_getException()");
                }

                currentBlock = block;
                exceptionThrown = false;
                for (int j = 0; j < block.instructionCount(); ++j) {
                    this.callSiteLiveIns = blockLiveIns.get(j);
                    updateShadowStack();
                    block.readInstruction(j, reader);
                    block.getInstructions().get(j).acceptVisitor(defExtractor);
                    for (Variable def : defExtractor.getDefinedVariables()) {
                        List<Variable> receivers = joints.get(def);
                        if (receivers != null) {
                            for (Variable receiver : receivers) {
                                currentJointValues.put(receiver, def);
                            }
                        }
                    }
                    addExceptionHandler();
                    flushInstructions();
                }
                if (exceptionThrown) {
                    addTerminalLabel();
                    emitted.add("br label %error");
                    flushInstructions();
                }

                addCatches();
                flushInstructions();
            }

            if (errorBlockNeeded) {
                appendable.append("error:\n");
                appendable.append("    br label %exit\n");
                returnBlocks.add("error");
                returnVariables.add(defaultValue(method.getResultType()));
            }

            if (!returnBlocks.isEmpty()) {
                appendable.append("exit:\n");
                String returnType = renderType(method.getResultType());
                String returnVariable;
                if (!returnVariables.isEmpty()) {
                    if (returnVariables.size() == 1) {
                        returnVariable = returnVariables.get(0);
                    } else {
                        returnVariable = "%return";
                        StringBuilder sb = new StringBuilder();
                        sb.append("%return = phi " + returnType + " ");
                        for (int i = 0; i < returnVariables.size(); ++i) {
                            if (i > 0) {
                                sb.append(", ");
                            }
                            sb.append("[" + returnVariables.get(i) + ", %" + returnBlocks.get(i) + "]");
                        }
                        appendable.append("    " + sb + "\n");
                    }
                } else {
                    returnVariable = null;
                }
                appendable.append("    %stackRestore = load %teavm.stackFrame*, %teavm.stackFrame** %stackNext\n");
                appendable.append("    store %teavm.stackFrame* %stackRestore, "
                        + "%teavm.stackFrame** @teavm.stackTop;\n");
                if (method.getResultType() == ValueType.VOID) {
                    appendable.append("    ret void\n");
                } else {
                    appendable.append("    ret " + returnType + " " + returnVariable + "\n");
                }
            }
        }

        appendable.append("}\n");
    }

    private void findExceptionHandlerTransitions(Program program) {
        for (int i = 0; i < program.basicBlockCount(); ++i) {
            IntSet transitions = new IntOpenHashSet();
            for (TryCatchBlock tryCatch : program.basicBlockAt(i).getTryCatchBlocks()) {
                transitions.add(tryCatch.getHandler().getIndex());
            }
            exceptionHandlerTransitions.add(transitions);
            exceptionHandlerBackTransitions.add(new IntOpenHashSet());
        }
        for (int i = 0; i < program.basicBlockCount(); ++i) {
            for (TryCatchBlock tryCatch : program.basicBlockAt(i).getTryCatchBlocks()) {
                exceptionHandlerBackTransitions.get(tryCatch.getHandler().getIndex()).add(i);
            }
        }
    }

    private List<IntObjectMap<BitSet>> findCallSiteLiveIns(MethodReader method) {
        List<IntObjectMap<BitSet>> liveOut = new ArrayList<>();

        LivenessAnalyzer livenessAnalyzer = new LivenessAnalyzer();
        livenessAnalyzer.analyze(program);
        DefinitionExtractor defExtractor = new DefinitionExtractor();

        for (int i = 0; i < program.basicBlockCount(); ++i) {
            BasicBlock block = program.basicBlockAt(i);
            IntObjectMap<BitSet> blockLiveIn = new IntObjectOpenHashMap<>();
            liveOut.add(blockLiveIn);
            BitSet currentLiveOut = new BitSet();
            for (int successor : cfg.outgoingEdges(i)) {
                currentLiveOut.or(livenessAnalyzer.liveIn(successor));
            }
            for (int j = block.getInstructions().size() - 1; j >= 0; --j) {
                Instruction insn = block.getInstructions().get(j);
                insn.acceptVisitor(defExtractor);
                for (Variable definedVar : defExtractor.getDefinedVariables()) {
                    currentLiveOut.clear(definedVar.getIndex());
                }
                if (insn instanceof InvokeInstruction || insn instanceof InitClassInstruction
                        || insn instanceof ConstructInstruction || insn instanceof ConstructArrayInstruction
                        || insn instanceof CloneArrayInstruction || insn instanceof RaiseInstruction) {
                    BitSet csLiveIn = (BitSet) currentLiveOut.clone();
                    for (int v = csLiveIn.nextSetBit(0); v >= 0; v = csLiveIn.nextSetBit(v + 1)) {
                        if (!isReference(v)) {
                            csLiveIn.clear(v);
                        }
                    }
                    csLiveIn.clear(0, method.parameterCount() + 1);
                    blockLiveIn.put(j, csLiveIn);
                }
            }
            if (block.getExceptionVariable() != null) {
                currentLiveOut.clear(block.getExceptionVariable().getIndex());
            }
        }

        return liveOut;
    }

    private void updateShadowStack() {
        if (callSiteLiveIns == null) {
            return;
        }

        String stackType = "[" + stackFrameSize + " x i8*]";
        int cellIndex = 0;
        for (int i = callSiteLiveIns.nextSetBit(0); i >= 0; i = callSiteLiveIns.nextSetBit(i + 1)) {
            String stackCell = "%t" + temporaryVariable++;
            emitted.add(stackCell + " = getelementptr " + stackType + ", " + stackType + "* %stackData, "
                    + "i32 0, i32 " + cellIndex++);
            emitted.add("store i8* %v" + i + ", i8**" + stackCell);
        }
        while (cellIndex < stackFrameSize) {
            String stackCell = "%t" + temporaryVariable++;
            emitted.add(stackCell + " = getelementptr " + stackType + ", " + stackType + "* %stackData, "
                    + "i32 0, i32 " + cellIndex++);
            emitted.add("store i8* null, i8** " + stackCell);
        }

        List<String> handlers = currentBlock.readTryCatchBlocks().stream()
                .map(TryCatchBlockReader::getExceptionType)
                .collect(Collectors.toList());
        lastCallSiteId = callSiteRegistry.add(new CallSite(handlers));

        emitted.add("store i32 " + lastCallSiteId + ", i32* %callSiteIdRef");
    }

    private void addExceptionHandler() {
        if (callSiteLiveIns == null) {
            return;
        }

        List<TryCatchBlock> tryCatchBlocks = currentBlock.getTryCatchBlocks();

        String handlerId = "%t" + temporaryVariable++;
        emitted.add(handlerId + " = load i32, i32* %callSiteIdRef");

        String continueLabel = "continue" + temporaryVariable++;
        List<String> handlerLabels = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("i32 " + lastCallSiteId + ", label %" + continueLabel);
        for (int i = 0; i < tryCatchBlocks.size(); ++i) {
            String label = "eh" + temporaryVariable++;
            handlerLabels.add(label);
            sb.append(" i32 " + (lastCallSiteId + i + 1) + ", label %" + label);
        }
        emitted.add("switch i32 " + handlerId + ", label %error [ " + sb + " ]");
        errorBlockNeeded = true;

        for (int i = 0; i < tryCatchBlocks.size(); ++i) {
            TryCatchBlock tryCatch = tryCatchBlocks.get(i);
            emitted.add(handlerLabels.get(i) + ":");
            emitted.add("br label %b" + currentBlock.getIndex() + ".catch." + i);

            for (TryCatchJoint joint : tryCatch.getJoints()) {
                String sourceVariable = "%v" + currentJointValues.get(joint.getReceiver()).getIndex();
                String sourceBlock = "%" + handlerLabels.get(i);
                jointPhiIncomings.get(joint.getReceiver()).add(sourceVariable + ", " + sourceBlock);
            }
        }

        emitted.add(continueLabel + ":");
    }

    private void addCatches() {
        List<TryCatchBlock> tryCatchBlocks = currentBlock.getTryCatchBlocks();
        for (int i = 0; i < tryCatchBlocks.size(); ++i) {
            TryCatchBlockReader tryCatch = tryCatchBlocks.get(i);
            emitted.add("b" + currentBlock.getIndex() + ".catch." + i + ":");

            for (Map.Entry<Variable, List<String>> jointPhi : jointPhiIncomings.entrySet()) {
                int receiver = jointPhi.getKey().getIndex();
                String type = renderType(typeInferer.typeOf(receiver));
                if (jointPhi.getValue().isEmpty()) {
                    emitted.add("%v" + receiver + " = bitcast " + type + " %v" + firstJointValues[receiver]
                            + " to " + type);
                } else {
                    String incomingsText = jointPhi.getValue().stream()
                            .map(incoming -> "[ " + incoming + " ]")
                            .collect(Collectors.joining(", "));
                    emitted.add("%v" + receiver + " = phi " + type + " " + incomingsText);
                }
            }

            emitted.add("br label %b" + currentBlock.getIndex() + ".catch.to." + tryCatch.getHandler().getIndex());
        }

        for (int target : exceptionHandlerTransitions.get(currentBlock.getIndex()).toArray()) {
            emitted.add("b" + currentBlock.getIndex() + ".catch.to." + target + ":");
            emitted.add("br label %b" + target);
        }
    }

    private int[] getFirstJointValues(MethodReader method) {
        class Step {
            int node;
            boolean[] initialized = new boolean[program.variableCount()];
            public Step(int node) {
                this.node = node;
            }
        }

        int[] result = new int[program.variableCount()];
        Graph dom = GraphUtils.buildDominatorGraph(GraphUtils.buildDominatorTree(cfg), cfg.size());
        DefinitionExtractor defExtractor = new DefinitionExtractor();

        Step[] stack = new Step[cfg.size()];
        int depth = 0;
        Step first = new Step(0);
        for (int i = 0; i <= method.parameterCount(); ++i) {
            first.initialized[i] = true;
        }
        stack[depth++] = first;
        List<List<TryCatchJoint>> inputJoints = ProgramUtils.getInputJoints(program);

        while (depth > 0) {
            Step step = stack[--depth];
            int node = step.node;
            boolean[] initialized = step.initialized;

            BasicBlock block = program.basicBlockAt(node);
            for (Phi phi : block.getPhis()) {
                initialized[phi.getReceiver().getIndex()] = true;
            }
            for (TryCatchJoint joint : inputJoints.get(node)) {
                initialized[joint.getReceiver().getIndex()] = true;
            }

            for (TryCatchBlock tryCatch : block.getTryCatchBlocks()) {
                for (TryCatchJoint joint : tryCatch.getJoints()) {
                    Variable initializedVar = joint.getSourceVariables().stream()
                            .filter(variable -> initialized[variable.getIndex()])
                            .findFirst()
                            .orElse(null);
                    if (initializedVar != null) {
                        result[joint.getReceiver().getIndex()] = initializedVar.getIndex();
                    }
                }
            }

            for (Instruction insn : block.getInstructions()) {
                insn.acceptVisitor(defExtractor);
                for (Variable def : defExtractor.getDefinedVariables()) {
                    initialized[def.getIndex()] = true;
                }
            }

            for (int successor : dom.outgoingEdges(node)) {
                Step next = new Step(successor);
                System.arraycopy(initialized, 0, next.initialized, 0, next.initialized.length);
                stack[depth++] = next;
            }
        }

        return result;
    }

    private boolean isReference(int var) {
        VariableType liveType = typeInferer.typeOf(var);
        switch (liveType) {
            case BYTE_ARRAY:
            case CHAR_ARRAY:
            case SHORT_ARRAY:
            case INT_ARRAY:
            case FLOAT_ARRAY:
            case LONG_ARRAY:
            case DOUBLE_ARRAY:
            case OBJECT_ARRAY:
            case OBJECT:
                return true;
            default:
                return false;
        }
    }

    private int getStackFrameSize(List<IntObjectMap<BitSet>> liveIn) {
        int max = 0;
        for (IntObjectMap<BitSet> blockLiveOut : liveIn) {
            for (ObjectCursor<BitSet> callSiteLiveOutCursor : blockLiveOut.values()) {
                BitSet callSiteLiveOut = callSiteLiveOutCursor.value;
                max = Math.max(max, callSiteLiveOut.cardinality());
            }
        }
        return max;
    }

    private void flushInstructions() throws IOException {
        for (String emittedLine : emitted) {
            appendable.append("    " + emittedLine + "\n");
        }
        emitted.clear();
    }

    private void addTerminalLabel() {
        String label = "b" + currentBlock.getIndex() + ".exit";
        emitted.add("br label %" + label);
        emitted.add(label + ":");
    }

    private InstructionReader reader = new InstructionReader() {
        @Override
        public void location(InstructionLocation location) {
        }

        @Override
        public void nop() {
        }

        @Override
        public void classConstant(VariableReader receiver, ValueType cst) {
            emitted.add("%v" + receiver.getIndex() + " = bitcast i8* null to i8 *");
        }

        @Override
        public void nullConstant(VariableReader receiver) {
            emitted.add("%v" + receiver.getIndex() + " = bitcast i8* null to i8 *");
        }

        @Override
        public void integerConstant(VariableReader receiver, int cst) {
            emitted.add("%v" + receiver.getIndex() + " = add i32 " + cst + ", 0");
        }

        @Override
        public void longConstant(VariableReader receiver, long cst) {
            emitted.add("%v" + receiver.getIndex() + " = add i64 " + cst + ", 0");
        }

        @Override
        public void floatConstant(VariableReader receiver, float cst) {
            String constantString = "0x" + Long.toHexString(Double.doubleToLongBits(cst));
            emitted.add("%v" + receiver.getIndex() + " = fadd float " + constantString + ", 0.0");
        }

        @Override
        public void doubleConstant(VariableReader receiver, double cst) {
            String constantString = "0x" + Long.toHexString(Double.doubleToLongBits(cst));
            emitted.add("%v" + receiver.getIndex() + " = fadd double " + constantString + ", 0.0");
        }

        @Override
        public void stringConstant(VariableReader receiver, String cst) {
            int index = stringPool.lookup(cst);
            emitted.add("%v" + receiver.getIndex() + " = bitcast %class.java.lang.String* @teavm.str."
                    + index + " to i8*");
        }

        @Override
        public void binary(BinaryOperation op, VariableReader receiver, VariableReader first, VariableReader second,
                NumericOperandType type) {
            StringBuilder sb = new StringBuilder();
            sb.append("%v" + receiver.getIndex() + " = ");
            boolean isFloat = type == NumericOperandType.FLOAT || type == NumericOperandType.DOUBLE;
            String typeStr = getLLVMType(type);

            String secondString = "%v" + second.getIndex();
            switch (op) {
                case ADD:
                    sb.append(isFloat ? "fadd" : "add");
                    break;
                case SUBTRACT:
                    sb.append(isFloat ? "fsub" : "sub");
                    break;
                case MULTIPLY:
                    sb.append(isFloat ? "fmul" : "mul");
                    break;
                case DIVIDE:
                    sb.append(isFloat ? "fdiv" : "sdiv");
                    break;
                case MODULO:
                    sb.append(isFloat ? "frem" : "srem");
                    break;
                case AND:
                    sb.append("and");
                    break;
                case OR:
                    sb.append("or");
                    break;
                case XOR:
                    sb.append("xor");
                    break;
                case SHIFT_LEFT:
                    sb.append("shl");
                    break;
                case SHIFT_RIGHT:
                    sb.append("ashr");
                    break;
                case SHIFT_RIGHT_UNSIGNED:
                    sb.append("lshr");
                    break;
                case COMPARE:
                    sb.append("call i32 @teavm.cmp.");
                    sb.append(typeStr + "(" + typeStr + " %v" + first.getIndex() + ", "
                            + typeStr + " %v" + second.getIndex() + ")");
                    emitted.add(sb.toString());
                    return;
            }
            if (type == NumericOperandType.LONG) {
                switch (op) {
                    case SHIFT_LEFT:
                    case SHIFT_RIGHT:
                    case SHIFT_RIGHT_UNSIGNED: {
                        int tmp = temporaryVariable++;
                        emitted.add("%t" + tmp + " = sext i32 " + secondString + " to i64");
                        secondString = "%t" + tmp;
                        break;
                    }
                    default:
                        break;
                }
            }

            sb.append(" ").append(typeStr).append(" %v" + first.getIndex() + ", " + secondString);
            emitted.add(sb.toString());
        }

        @Override
        public void negate(VariableReader receiver, VariableReader operand, NumericOperandType type) {
            emitted.add("%v" + receiver.getIndex() + " = sub " + getLLVMType(type) + " 0, %v" + operand.getIndex());
        }

        @Override
        public void assign(VariableReader receiver, VariableReader assignee) {
            String type = renderType(typeInferer.typeOf(receiver.getIndex()));
            emitted.add("%v" + receiver.getIndex() + " = bitcast " + type + " %v" + assignee.getIndex()
                    + " to " + type);
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, ValueType targetType) {
            emitted.add("%v" + receiver.getIndex() + " = bitcast i8* %v" + value.getIndex() + " to i8*");
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, NumericOperandType sourceType,
                NumericOperandType targetType) {
            switch (sourceType) {
                case INT:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            emitted.add("%v" + receiver.getIndex() + " = sext i32 %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            emitted.add("%v" + receiver.getIndex() + " = sitofp i32 %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                    }
                    break;
                case LONG:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            emitted.add("%v" + receiver.getIndex() + " = trunc i64 %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            emitted.add("%v" + receiver.getIndex() + " = sitofp i64 %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                    }
                    break;
                case FLOAT:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            emitted.add("%v" + receiver.getIndex() + " = fptosi float %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            emitted.add("%v" + receiver.getIndex() + " = fpext float %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                    }
                    break;
                case DOUBLE:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            emitted.add("%v" + receiver.getIndex() + " = fptosi double %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            emitted.add("%v" + receiver.getIndex() + " = fptrunc double %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                    }
                    break;
            }
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, IntegerSubtype type,
                CastIntegerDirection direction) {
            int tmp = temporaryVariable++;
            switch (direction) {
                case TO_INTEGER:
                    emitted.add("%v" + receiver.getIndex() + " = bitcast i32 %v" + value.getIndex() + " to i32");
                    break;
                case FROM_INTEGER:
                    switch (type) {
                        case BYTE:
                            emitted.add("%t" + tmp + " = trunc i32 %v" + value.getIndex() + " to i8");
                            emitted.add("%v" + receiver.getIndex() + " = sext i8 %t" + tmp + " to i32");
                            break;
                        case SHORT:
                            emitted.add("%t" + tmp + " = trunc i32 %v" + value.getIndex() + " to i16");
                            emitted.add("%t" + receiver.getIndex() + " = sext i16 %t" + tmp + " to i32");
                            break;
                        case CHARACTER:
                            emitted.add("%t" + tmp + " = trunc i32 %v" + value.getIndex() + " to i16");
                            emitted.add("%v" + receiver.getIndex() + " = zext i16 %t" + tmp + " to i32");
                            break;
                    }
                    break;
            }
        }

        @Override
        public void jumpIf(BranchingCondition cond, VariableReader operand, BasicBlockReader consequent,
                BasicBlockReader alternative) {
            addTerminalLabel();
            int tmp = temporaryVariable++;
            String type = "i32";
            String second = "0";
            if (cond == BranchingCondition.NULL || cond == BranchingCondition.NOT_NULL) {
                type = "i8*";
                second = "null";
            }

            emitted.add("%t" + tmp + " = icmp " + getLLVMOperation(cond) + " " + type
                    + " %v" + operand.getIndex() + ", " + second);
            emitted.add("br i1 %t" + tmp + ", label %b" + consequent.getIndex() + ", label %b"
                    + alternative.getIndex());
        }

        @Override
        public void jumpIf(BinaryBranchingCondition cond, VariableReader first, VariableReader second,
                BasicBlockReader consequent, BasicBlockReader alternative) {
            addTerminalLabel();
            int tmp = temporaryVariable++;

            String type = "i32";
            String op;
            switch (cond) {
                case EQUAL:
                    op = "eq";
                    break;
                case NOT_EQUAL:
                    op = "ne";
                    break;
                case REFERENCE_EQUAL:
                    op = "eq";
                    type = "i8*";
                    break;
                case REFERENCE_NOT_EQUAL:
                    op = "ne";
                    type = "i8*";
                    break;
                default:
                    throw new IllegalArgumentException("Unknown condition: " + cond);
            }

            emitted.add("%t" + tmp + " = icmp " + op + " " + type + " %v" + first.getIndex()
                    + ", %v" + second.getIndex());
            emitted.add("br i1 %t" + tmp + ", label %b" + consequent.getIndex() + ", label %b"
                    + alternative.getIndex());
        }

        @Override
        public void jump(BasicBlockReader target) {
            addTerminalLabel();
            emitted.add("br label %b" + target.getIndex());
        }

        @Override
        public void choose(VariableReader condition, List<? extends SwitchTableEntryReader> table,
                BasicBlockReader defaultTarget) {
            addTerminalLabel();
            StringBuilder sb = new StringBuilder();
            sb.append("switch i32 %v" + condition.getIndex() + ", label %b" + defaultTarget.getIndex() + " [");
            for (SwitchTableEntryReader entry : table) {
                sb.append(" i32 " + entry.getCondition() + ", label %b" + entry.getTarget().getIndex());
            }
            sb.append(" ]");
            emitted.add(sb.toString());
        }

        @Override
        public void exit(VariableReader valueToReturn) {
            addTerminalLabel();
            if (valueToReturn == null) {
                emitted.add("br label %exit");
            } else {
                String returnVar = "%v" + valueToReturn.getIndex();
                returnVariables.add(returnVar);
                emitted.add("br label %exit");
            }
            returnBlocks.add("b" + currentBlock.getIndex() + ".exit");
        }

        @Override
        public void raise(VariableReader exception) {
            emitted.add("call void @teavm_throw(i8* %v" + exception.getIndex() + ")");
            exceptionThrown = true;
        }

        @Override
        public void createArray(VariableReader receiver, ValueType itemType, VariableReader size) {
            if (itemType instanceof ValueType.Primitive) {
                String functionName = getJavaTypeName(((ValueType.Primitive) itemType).getKind());
                functionName = "@teavm_" + functionName + "ArrayAlloc";
                emitted.add("%v" + receiver.getIndex() + " = call i8* " + functionName
                        + "(i32 %v" + size.getIndex() + ")");
                return;
            }

            int depth = 0;
            while (itemType instanceof ValueType.Array) {
                ++depth;
                itemType = ((ValueType.Array) itemType).getItemType();
            }

            String itemTypeRef;
            if (itemType instanceof ValueType.Object) {
                String className = ((ValueType.Object) itemType).getClassName();
                itemTypeRef = "%vtable." + className + "* @vtable." + className;
            } else if (itemType instanceof ValueType.Primitive) {
                String primitiveName = getJavaTypeName(((ValueType.Primitive) itemType).getKind());
                itemTypeRef = "%itable* @teavm." + primitiveName + "Array";
            } else {
                throw new AssertionError("Type is not expected here: " + itemType);
            }

            String tag = "i32 lshr (i32 ptrtoint (" + itemTypeRef + " to i32), i32 3)";
            emitted.add("%v" + receiver.getIndex() + " = call i8* @teavm_objectArrayAlloc(" + tag
                    + ", i8 " + depth + ", i32 %v" + size.getIndex() + ")");
        }

        @Override
        public void createArray(VariableReader receiver, ValueType itemType,
                List<? extends VariableReader> dimensions) {

        }

        @Override
        public void create(VariableReader receiver, String type) {
            String typeRef = "vtable." + type;
            String tag = "i32 lshr (i32 ptrtoint (%" + typeRef + "* @" + typeRef + " to i32), i32 3)";
            emitted.add("%v" + receiver.getIndex() + " = call i8* @teavm_alloc(" + tag + ")");
        }

        @Override
        public void getField(VariableReader receiver, VariableReader instance, FieldReference field,
                ValueType fieldType) {
            String valueTypeRef = renderType(fieldType);
            if (instance == null) {
                emitted.add("%v" + receiver.getIndex() + " = load " + valueTypeRef + ", "
                        + valueTypeRef + "* @" + mangleField(field));
            } else {
                int typedInstance = temporaryVariable++;
                int pointer = temporaryVariable++;
                String typeRef = "%class." + field.getClassName();
                emitted.add("%t" + typedInstance + " = bitcast i8* %v" + instance.getIndex() + " to " + typeRef + "*");
                emitted.add("%t" + pointer + " = getelementptr " + typeRef + ", " + typeRef + "* "
                        + "%t" + typedInstance + ", i32 0, i32 " + layoutProvider.getIndex(field));
                emitted.add("%v" + receiver.getIndex() + " = load " + valueTypeRef + ", "
                        + valueTypeRef + "* %t" + pointer);
            }
        }

        @Override
        public void putField(VariableReader instance, FieldReference field, VariableReader value, ValueType fieldType) {
            String valueTypeRef = renderType(fieldType);
            if (instance == null) {
                emitted.add("store " + valueTypeRef + " %v" + value.getIndex() + ", "
                        + valueTypeRef + "* @" + mangleField(field));
            } else {
                int typedInstance = temporaryVariable++;
                int pointer = temporaryVariable++;
                String typeRef = "%class." + field.getClassName();
                emitted.add("%t" + typedInstance + " = bitcast i8* %v" + instance.getIndex() + " to " + typeRef + "*");
                emitted.add("%t" + pointer + " = getelementptr " + typeRef + ", " + typeRef + "* "
                        + "%t" + typedInstance + ", i32 0, i32 " + layoutProvider.getIndex(field));
                emitted.add("store " + valueTypeRef + " %v" + value.getIndex() + ", "
                        + valueTypeRef + "* %t" + pointer);
            }
        }

        @Override
        public void arrayLength(VariableReader receiver, VariableReader array) {
            arrayLength(array, "%v" + receiver.getIndex());
        }

        private void arrayLength(VariableReader array, String target) {
            int objectRef = temporaryVariable++;
            int headerRef = temporaryVariable++;
            emitted.add("%t" + objectRef + " = bitcast i8* %v" + array.getIndex() + " to %teavm.Array*");
            emitted.add("%t" + headerRef + " = getelementptr %teavm.Array, %teavm.Array* %t"
                    + objectRef + ", i32 0, i32 1");
            emitted.add(target + " = load i32, i32* %t" + headerRef);
        }

        @Override
        public void cloneArray(VariableReader receiver, VariableReader array) {
            emitted.add("%v" + receiver.getIndex() + " = call i8* @teavm_cloneArray(i8* %v" + array.getIndex() + ")");
        }

        @Override
        public void unwrapArray(VariableReader receiver, VariableReader array, ArrayElementType elementType) {
            emitted.add("%v" + receiver.getIndex() + " = bitcast i8* %v" + array.getIndex() + " to i8*");
        }

        @Override
        public void getElement(VariableReader receiver, VariableReader array, VariableReader index) {
            String type = renderType(typeInferer.typeOf(receiver.getIndex()));
            VariableType itemType = typeInferer.typeOf(array.getIndex());
            String itemTypeStr = renderItemType(itemType);
            int elementRef = getArrayElementReference(array, index, itemTypeStr);
            if (type.equals(itemTypeStr)) {
                emitted.add("%v" + receiver.getIndex() + " = load " + type + ", " + type + "* %t" + elementRef);
            } else {
                int tmp = temporaryVariable++;
                emitted.add("%t" + tmp + " = load " + itemTypeStr + ", " + itemTypeStr + "* %t" + elementRef);
                switch (itemType) {
                    case BYTE_ARRAY:
                        emitted.add("%v" + receiver.getIndex() + " = sext i8 %t" + tmp + " to i32");
                        break;
                    case SHORT_ARRAY:
                        emitted.add("%v" + receiver.getIndex() + " = sext i16 %t" + tmp + " to i32");
                        break;
                    case CHAR_ARRAY:
                        emitted.add("%v" + receiver.getIndex() + " = zext i16 %t" + tmp + " to i32");
                        break;
                    default:
                        throw new AssertionError("Should not get here");
                }
            }
        }

        @Override
        public void putElement(VariableReader array, VariableReader index, VariableReader value) {
            String type = renderType(typeInferer.typeOf(value.getIndex()));
            VariableType itemType = typeInferer.typeOf(array.getIndex());
            String itemTypeStr = renderItemType(itemType);
            int elementRef = getArrayElementReference(array, index, itemTypeStr);
            String valueRef = "%v" + value.getIndex();
            if (!type.equals(itemTypeStr)) {
                int tmp = temporaryVariable++;
                emitted.add("%t" + tmp + " = trunc i32 " + valueRef + " to " + itemTypeStr);
                valueRef = "%t" + tmp;
            }
            emitted.add("store " + itemTypeStr + " " + valueRef + ", " + itemTypeStr + "* %t" + elementRef);
        }

        private int getArrayElementReference(VariableReader array, VariableReader index, String type) {
            int objectRef = temporaryVariable++;
            int dataRef = temporaryVariable++;
            int typedDataRef = temporaryVariable++;
            int adjustedIndex = temporaryVariable++;
            int elementRef = temporaryVariable++;
            emitted.add("%t" + objectRef + " = bitcast i8* %v" + array.getIndex() + " to %teavm.Array*");
            emitted.add("%t" + dataRef + " = getelementptr %teavm.Array, %teavm.Array* %t"
                    + objectRef + ", i32 1");
            emitted.add("%t" + typedDataRef + " = bitcast %teavm.Array* %t" + dataRef + " to " + type + "*");
            emitted.add("%t" + adjustedIndex + " = add i32 %v" + index.getIndex() + ", 1");
            emitted.add("%t" + elementRef + " = getelementptr " + type + ", " + type + "* %t" + typedDataRef
                    + ", i32 %t" + adjustedIndex);

            return elementRef;
        }

        @Override
        public void invoke(VariableReader receiver, VariableReader instance, MethodReference method,
                List<? extends VariableReader> arguments, InvocationType type) {
            StringBuilder sb = new StringBuilder();
            if (receiver != null) {
                sb.append("%v" + receiver.getIndex() + " = ");
            }

            String functionText;
            if (type == InvocationType.SPECIAL) {
                functionText = "@" + mangleMethod(method);
            } else {
                VirtualTableEntry entry = resolve(method);
                String className = entry.getVirtualTable().getClassName();
                String typeRef = className != null ? "%vtable." + className : "%itable";
                int objectRef = temporaryVariable++;
                int headerFieldRef = temporaryVariable++;
                int vtableTag = temporaryVariable++;
                int vtableRef = temporaryVariable++;
                int vtableTypedRef = temporaryVariable++;
                emitted.add("%t" + objectRef + " = bitcast i8* %v" + instance.getIndex() + " to %teavm.Object*");
                emitted.add("%t" + headerFieldRef + " = getelementptr inbounds %teavm.Object, %teavm.Object* %t"
                        + objectRef + ", i32 0, i32 0");
                emitted.add("%t" + vtableTag + " = load i32, i32* %t" + headerFieldRef);
                emitted.add("%t" + vtableRef + " = shl i32 %t" + vtableTag + ", 3");
                emitted.add("%t" + vtableTypedRef + " = inttoptr i32 %t" + vtableRef + " to " + typeRef + "*");

                int functionRef = temporaryVariable++;
                int vtableIndex = entry.getIndex() + 1;

                emitted.add("%t" + functionRef + " = getelementptr inbounds " + typeRef + ", "
                        + typeRef + "* %t" + vtableTypedRef + ", i32 0, i32 " + vtableIndex);
                int function = temporaryVariable++;
                String methodType = methodType(method.getDescriptor());
                emitted.add("%t" + function + " = load " + methodType + ", " + methodType + "* %t" + functionRef);

                functionText = "%t" + function;
            }

            sb.append("call " + renderType(method.getReturnType()) + " " + functionText + "(");

            List<String> argumentStrings = new ArrayList<>();
            if (instance != null) {
                argumentStrings.add("i8* %v" + instance.getIndex());
            }
            for (int i = 0; i < arguments.size(); ++i) {
                argumentStrings.add(renderType(method.parameterType(i)) + " %v" + arguments.get(i).getIndex());
            }
            sb.append(argumentStrings.stream().collect(Collectors.joining(", ")) + ")");

            emitted.add(sb.toString());
        }

        private VirtualTableEntry resolve(MethodReference method) {
            while (true) {
                VirtualTableEntry entry = vtableProvider.lookup(method);
                if (entry != null) {
                    return entry;
                }
                ClassReader cls = classSource.get(method.getClassName());
                if (cls == null || cls.getParent() == null || cls.getParent().equals(cls.getName())) {
                    break;
                }
                method = new MethodReference(cls.getParent(), method.getDescriptor());
            }
            return null;
        }

        @Override
        public void invokeDynamic(VariableReader receiver, VariableReader instance, MethodDescriptor method,
                List<? extends VariableReader> arguments, MethodHandle bootstrapMethod,
                List<RuntimeConstant> bootstrapArguments) {

        }

        @Override
        public void isInstance(VariableReader receiver, VariableReader value, ValueType type) {
            if (type instanceof ValueType.Object) {
                String className = ((ValueType.Object) type).getClassName();
                List<TagRegistry.Range> ranges = tagRegistry.getRanges(className);

                if (!ranges.isEmpty()) {
                    String headerRef = "%t" + temporaryVariable++;
                    emitted.add(headerRef + " = bitcast i8* %v" + value.getIndex() + " to %teavm.Object*");
                    String vtableRefRef = "%t" + temporaryVariable++;
                    emitted.add(vtableRefRef + " = getelementptr %teavm.Object, %teavm.Object* " + headerRef + ", "
                            + "i32 0, i32 0");
                    String vtableTag = "%t" + temporaryVariable++;
                    emitted.add(vtableTag + " = load i32, i32* " + vtableRefRef);
                    String vtableRef = "%t" + temporaryVariable++;
                    emitted.add(vtableRef + " = shl i32 " + vtableTag + ", 3");
                    String typedVtableRef = "%t" + temporaryVariable++;
                    emitted.add(typedVtableRef + " = inttoptr i32 " + vtableRef + " to %teavm.Class*");
                    String tagRef = "%t" + temporaryVariable++;
                    emitted.add(tagRef + " = getelementptr %teavm.Class, %teavm.Class* " + typedVtableRef
                            + ", i32 0, i32 2");
                    String tag = "%t" + temporaryVariable++;
                    emitted.add(tag + " = load i32, i32* " + tagRef);

                    String trueLabel = "tb" + temporaryVariable++;
                    String finalLabel = "tb" + temporaryVariable++;
                    String next = null;
                    for (TagRegistry.Range range : ranges) {
                        String tmpLabel = "tb" + temporaryVariable++;
                        next = "tb" + temporaryVariable++;
                        String tmpLower = "%t" + temporaryVariable++;
                        String tmpUpper = "%t" + temporaryVariable++;
                        emitted.add(tmpLower + " = icmp slt i32 " + tag + ", " + range.lower);
                        emitted.add("br i1 " + tmpLower + ", label %" + next + ", label %" + tmpLabel);
                        emitted.add(tmpLabel + ":");
                        emitted.add(tmpUpper + " = icmp sge i32 " + tag + ", " + range.upper);
                        emitted.add("br i1 " + tmpUpper + ", label %" + next + ", label %" + trueLabel);
                        emitted.add(next + ":");
                    }

                    String falseVar = "%t" + temporaryVariable++;
                    emitted.add(falseVar + " = add i32 0, 0");
                    emitted.add("br label %" + finalLabel);

                    String trueVar = "%t" + temporaryVariable++;
                    emitted.add(trueLabel + ":");
                    emitted.add(trueVar + " = add i32 1, 0");
                    emitted.add("br label %" + finalLabel);

                    String phiVar = "%t" + temporaryVariable++;
                    emitted.add(finalLabel + ":");
                    emitted.add(phiVar + " = phi i32 [ " + trueVar + ", "
                            + "%" + trueLabel + " ], [ " + falseVar + ", %" + next + "]");
                    emitted.add("%v" + receiver.getIndex() + " = add i32 0, " + phiVar);
                } else {
                    emitted.add("%v" + receiver.getIndex() + " = add i32 0, 0");
                }
            } else {
                emitted.add("%v" + receiver.getIndex() + " = add i32 1, 0");
            }
        }

        @Override
        public void initClass(String className) {
            emitted.add("call void @initializer$" + className + "()");
        }

        @Override
        public void nullCheck(VariableReader receiver, VariableReader value) {
        }

        @Override
        public void monitorEnter(VariableReader objectRef) {
        }

        @Override
        public void monitorExit(VariableReader objectRef) {
        }
    };

    private static String getLLVMType(NumericOperandType type) {
        switch (type) {
            case INT:
                return "i32";
            case LONG:
                return "i64";
            case FLOAT:
                return "float";
            case DOUBLE:
                return "double";
        }
        throw new IllegalArgumentException("Unknown operand type: " + type);
    }

    private static String getLLVMOperation(BranchingCondition cond) {
        switch (cond) {
            case EQUAL:
            case NULL:
                return "eq";
            case NOT_NULL:
            case NOT_EQUAL:
                return "ne";
            case GREATER:
                return "sgt";
            case GREATER_OR_EQUAL:
                return "sge";
            case LESS:
                return "slt";
            case LESS_OR_EQUAL:
                return "sle";
        }
        throw new IllegalArgumentException("Unsupported condition: " + cond);
    }
}
