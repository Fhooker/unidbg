package com.github.unidbg.ios.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.ios.MachOLoader;
import com.github.unidbg.ios.struct.kernel.Pthread;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.Thread;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;

public class DarwinThread extends Thread {

    private static final Log log = LogFactory.getLog(DarwinThread.class);

    private static final int THREAD_STACK_SIZE = 0x80000;

    private final UnidbgPointer start_routine;
    private final UnidbgPointer arg;
    private final UnidbgPointer stack;
    private final Pthread pthread;

    public DarwinThread(Emulator<?> emulator, UnidbgPointer start_routine, UnidbgPointer arg, long until, Pthread pthread) {
        super(until);

        this.start_routine = start_routine;
        this.arg = arg;
        this.pthread = pthread;

        MemoryBlock block = emulator.getMemory().malloc(THREAD_STACK_SIZE, true);
        this.stack = block.getPointer().share(THREAD_STACK_SIZE, 0);

        pthread.setStack(stack, THREAD_STACK_SIZE);
        pthread.pack();
    }

    @Override
    public String toString() {
        return "DarwinThread start_routine=" + start_routine + ", arg=" + arg;
    }

    @Override
    protected Number runThread(AbstractEmulator<?> emulator) {
        Backend backend = emulator.getBackend();
        backend.reg_write(ArmConst.UC_ARM_REG_R0, this.arg == null ? 0L : this.arg.peer);
        backend.reg_write(ArmConst.UC_ARM_REG_SP, this.stack.peer);

        UnidbgPointer tsd = pthread.getTSD();
        tsd.setPointer(MachOLoader.__TSD_THREAD_SELF * emulator.getPointerSize(), pthread.getPointer());
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_C13_C0_3, tsd.peer);
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_TPIDRRO_EL0, tsd.peer);
        }

        return emulator.emulate(this.start_routine.peer, until);
    }

    private long context;

    @Override
    public void runThread(Emulator<?> emulator, long __thread_entry, long timeout) {
        if (emulator.is32Bit()) {
            throw new UnsupportedOperationException();
        }
        Backend backend = emulator.getBackend();
        if (this.context == 0) {
            log.info("run thread: start_routine=" + this.start_routine + ", arg=" + this.arg + ", stack=" + this.stack);
            UnidbgPointer tsd = pthread.getTSD();
            backend.reg_write(Arm64Const.UC_ARM64_REG_TPIDRRO_EL0, tsd.peer);
            emulator.eThread(this.start_routine.peer, this.arg == null ? 0L : this.arg.peer, this.stack.peer);
        } else {
            backend.context_restore(this.context);
            long pc = backend.reg_read(Arm64Const.UC_ARM64_REG_PC).longValue();
            log.info("resume thread: start_routine=" + this.start_routine + ", arg=" + this.arg + ", stack=" + this.stack + ", pc=0x" + Long.toHexString(pc));
            backend.emu_start(pc, 0, timeout, 0);
        }
        if (this.context == 0) {
            this.context = backend.context_alloc();
        }
        backend.context_save(this.context);
    }

}
