#include <iostream>

#include "agent.h"
#include "config.h"

int main(int argc, char** argv) {
    (void)argc;
    (void)argv;

    AgentConfig config = loadConfig();
    Agent agent(config);
    return agent.run();
}

