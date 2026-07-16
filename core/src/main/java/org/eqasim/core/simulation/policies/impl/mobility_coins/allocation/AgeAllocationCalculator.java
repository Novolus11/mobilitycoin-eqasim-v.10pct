package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Age (AGE) allocation – single-factor scheme based on age.
 * TODO: implement score logic
 */
public class AgeAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(AgeAllocationCalculator.class);
    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;

    public AgeAllocationCalculator(Map<String, AgentParametersPrecomputer.AgentParams> agentParams) {
        this.agentParams = agentParams;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        // TODO: implement score-based allocation
        double coinsPerPerson = totalCoins / population.getPersons().size();
        Map<Id<Person>, Double> allocations = new HashMap<>();
        for (Person person : population.getPersons().values()) {
            allocations.put(person.getId(), coinsPerPerson);
        }
        return allocations;
    }

    @Override
    public String getDescription() {
        return "Age (AGE) single-factor allocation – TODO: implement";
    }
}
