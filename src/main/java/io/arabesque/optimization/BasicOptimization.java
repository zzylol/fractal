package io.arabesque.optimization;

import io.arabesque.conf.Configuration;

/**
 * Created by Alex on 25-Oct-15.
 */
public class BasicOptimization implements Optimization {
    protected Configuration configuration;

    @Override
    public void init(Configuration config) {
       configuration = config;
    }

    @Override
    public void applyStartup() {
        // Empty by design
    }

    @Override
    public void applyAfterGraphLoad() {
        // Empty by design
    }
}
