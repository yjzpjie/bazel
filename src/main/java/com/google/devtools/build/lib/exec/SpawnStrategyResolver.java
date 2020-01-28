// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnContinuation;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.actions.UserExecException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolver that looks up the right strategy for a spawn during {@link #exec} (via a {@link
 * SpawnStrategyRegistry}) and uses it to execute the spawn.
 */
public final class SpawnStrategyResolver implements ActionContext {

  /**
   * Executes the given spawn with the {@linkplain SpawnStrategyRegistry highest priority strategy}
   * that can be found for it.
   *
   * @param actionExecutionContext context in which to execute the spawn
   * @return result(s) from the spawn's execution
   */
  public ImmutableList<SpawnResult> exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    return resolveOne(spawn, actionExecutionContext).exec(spawn, actionExecutionContext);
  }

  /**
   * Queues execution of the given spawn with the {@linkplain SpawnStrategyRegistry highest priority
   * strategy} that can be found for it.
   *
   * @param actionExecutionContext context in which to execute the spawn
   * @return handle to the spawn's pending execution (or failure thereof)
   */
  public SpawnContinuation beginExecution(
      Spawn spawn, ActionExecutionContext actionExecutionContext) throws InterruptedException {
    SpawnStrategy resolvedStrategy;
    try {
      resolvedStrategy = resolveOne(spawn, actionExecutionContext);
    } catch (ExecException e) {
      return SpawnContinuation.failedWithExecException(e);
    }
    return resolvedStrategy.beginExecution(spawn, actionExecutionContext);
  }

  private SpawnStrategy resolveOne(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws UserExecException {
    List<? extends SpawnStrategy> strategies = resolve(spawn, actionExecutionContext);

    // Because the strategies are ordered by preference, we can execute the spawn with the best
    // possible one by simply filtering out the ones that can't execute it and then picking the
    // first one from the remaining strategies in the list.
    return strategies.get(0);
  }

  /**
   * Returns the list of {@link SpawnStrategy}s that should be used to execute the given spawn.
   *
   * @param spawn spawn for which the correct {@link SpawnStrategy} should be determined
   */
  @VisibleForTesting
  public List<? extends SpawnStrategy> resolve(
      Spawn spawn, ActionExecutionContext actionExecutionContext) throws UserExecException {
    List<? extends SpawnStrategy> strategies =
        actionExecutionContext
            .getContext(SpawnStrategyRegistry.class)
            .getStrategies(spawn, actionExecutionContext.getEventHandler());

    strategies =
        strategies.stream()
            .filter(spawnActionContext -> spawnActionContext.canExec(spawn, actionExecutionContext))
            .collect(Collectors.toList());

    if (strategies.isEmpty()) {
      throw new UserExecException(
          String.format(
              "No usable spawn strategy found for spawn with mnemonic %s.  Your"
                  + " --spawn_strategy, --genrule_strategy or --strategy flags are probably too"
                  + " strict. Visit https://github.com/bazelbuild/bazel/issues/7480 for"
                  + " migration advice",
              spawn.getMnemonic()));
    }

    return strategies;
  }
}