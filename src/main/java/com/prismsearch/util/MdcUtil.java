package com.prismsearch.util;

import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Helpers for propagating SLF4J {@link MDC} context across thread boundaries
 * (e.g. virtual-thread executors, async callbacks).
 */
public final class MdcUtil {

    private MdcUtil() {
    }

    /**
     * Wraps a {@link Supplier} so that the supplied MDC snapshot is restored
     * inside the executing thread and cleaned up afterwards.
     *
     * @param supplier   the original task
     * @param mdcContext MDC snapshot captured from the calling thread
     */
    public static <T> Supplier<T> wrap(Supplier<T> supplier, Map<String, String> mdcContext) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                return supplier.get();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wraps a {@link Runnable} so that the supplied MDC snapshot is restored
     * inside the executing thread and cleaned up afterwards.
     * <p>Use this overload when the task returns {@code void}.</p>
     *
     * @param runnable   the original task
     * @param mdcContext MDC snapshot captured from the calling thread
     */
    public static Runnable wrap(Runnable runnable, Map<String, String> mdcContext) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
