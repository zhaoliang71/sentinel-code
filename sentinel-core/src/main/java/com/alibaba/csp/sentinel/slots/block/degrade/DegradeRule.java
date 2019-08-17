/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.degrade;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

/**
 * <p>
 * Degrade is used when the resources are in an unstable state, these resources
 * will be degraded within the next defined time window. There are two ways to
 * measure whether a resource is stable or not:
 * </p>
 * <ul>
 * <li>
 * Average response time ({@code DEGRADE_GRADE_RT}): When
 * the average RT exceeds the threshold ('count' in 'DegradeRule', in milliseconds), the
 * resource enters a quasi-degraded state. If the RT of next coming 5
 * requests still exceed this threshold, this resource will be downgraded, which
 * means that in the next time window (defined in 'timeWindow', in seconds) all the
 * access to this resource will be blocked.
 * </li>
 * <li>
 * Exception ratio: When the ratio of exception count per second and the
 * success qps exceeds the threshold, access to the resource will be blocked in
 * the coming window.
 * </li>
 * </ul>
 *
 * @author jialiang.linjl
 */
public class DegradeRule extends AbstractRule {

    private static final int RT_MAX_EXCEED_N = 5;

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("sentinel-degrade-reset-task", true));

    public DegradeRule() {}

    public DegradeRule(String resourceName) {
        setResource(resourceName);
    }

    /**
     * RT threshold or exception ratio threshold count.
     */
    private double count;

    /**
     * Degrade recover timeout (in seconds) when degradation occurs.
     */
    private int timeWindow;

    /**
     * Degrade strategy (0: average RT, 1: exception ratio).
     */
    private int grade = RuleConstant.DEGRADE_GRADE_RT;

    private final AtomicBoolean cut = new AtomicBoolean(false);

    public int getGrade() {
        return grade;
    }

    public DegradeRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    private AtomicLong passCount = new AtomicLong(0);

    public double getCount() {
        return count;
    }

    public DegradeRule setCount(double count) {
        this.count = count;
        return this;
    }

    private boolean isCut() {
        return cut.get();
    }

    private void setCut(boolean cut) {
        this.cut.set(cut);
    }

    public AtomicLong getPassCount() {
        return passCount;
    }

    public int getTimeWindow() {
        return timeWindow;
    }

    public DegradeRule setTimeWindow(int timeWindow) {
        this.timeWindow = timeWindow;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DegradeRule)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        DegradeRule that = (DegradeRule)o;

        if (count != that.count) {
            return false;
        }
        if (timeWindow != that.timeWindow) {
            return false;
        }
        if (grade != that.grade) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + new Double(count).hashCode();
        result = 31 * result + timeWindow;
        result = 31 * result + grade;
        return result;
    }

    @Override
    public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
        //熔断后cut会被设置成true,timewindow之后由定时任务改为false
        if (cut.get()) {
            return false;
        }
        //获取ClusterBuilderSlot赋值的ClusterNode节点
        ClusterNode clusterNode = ClusterBuilderSlot.getClusterNode(this.getResource());
        if (clusterNode == null) {
            return true;
        }
        //RT熔断
        if (grade == RuleConstant.DEGRADE_GRADE_RT) {
            //获取平均RT
            double rt = clusterNode.avgRt();
            if (rt < this.count) {
                //平均RT小于配置值count时,则通过校验
                //如果校验通过则,设置passCount为0,以重新计算当前秒校验失败的此数
                passCount.set(0);
                return true;
            }

            // 如果校验失败的此数少于5次,则算做校验通过  RT_MAX_EXCEED_N:默认值5
            if (passCount.incrementAndGet() < RT_MAX_EXCEED_N) {
                return true;
            }
            //按异常比率熔断
        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO) {
            //当前秒的异常数
            double exception = clusterNode.exceptionQps();
            //当前秒的成功数
            double success = clusterNode.successQps();
            //当前秒的总数(校验成功(pass)+校验失败的(block))MetricEvent
            double total = clusterNode.totalQps();
            // 总数小于5次 不降级
            if (total < RT_MAX_EXCEED_N) {
                return true;
            }
            double realSuccess = success - exception;
            if (realSuccess <= 0 && exception < RT_MAX_EXCEED_N) {
                return true;
            }

            if (exception / success < count) {
                return true;
            }
            //按异常次数降级
        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT) {
            //获取当前一分钟的异常总次数
            //因为此处是一分钟, 所以,如果timeWindow的时间结束后,仍然处于当前这一分钟范围,则应该会直接降级,因为当前分钟异常次数超过了规则配置次数
            double exception = clusterNode.totalException();
            //总次数小于配置count 则校验通过
            if (exception < count) {
                return true;
            }
        }
        //已经校验失败,应该降级
        //将cut 设置为true,为true时直接回降级

        //unsafe.compareAndSwapInt(this, valueOffset, e, u)
        //expect表示期望的值,即遇到这个值,则将value改为update值,在这里就是遇到false便改成true,更新成功返回true
        //如果遇到的值不是expect值则不做任何处理,并返回个false
        if (cut.compareAndSet(false, true)) {
            //创建定时任务
            ResetTask resetTask = new ResetTask(this);
            //timeWindow时间后,执行resetTask这个定时任务
            pool.schedule(resetTask, timeWindow, TimeUnit.SECONDS);
        }

        return false;
    }

    @Override
    public String toString() {
        return "DegradeRule{" +
            "resource=" + getResource() +
            ", grade=" + grade +
            ", count=" + count +
            ", limitApp=" + getLimitApp() +
            ", timeWindow=" + timeWindow +
            "}";
    }
    //定时任务
    private static final class ResetTask implements Runnable {

        private DegradeRule rule;

        ResetTask(DegradeRule rule) {
            this.rule = rule;
        }

        @Override
        public void run() {
            //将校验失败次数设置为0,用来设定5次失败内不降级
            rule.getPassCount().set(0);
            //将cut设置为false,表示进行正常校验流程
            rule.cut.set(false);
        }
    }
}

