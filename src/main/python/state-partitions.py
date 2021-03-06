#!/usr/bin/env python

from __future__ import print_function

import argparse
import random
import re
import sys

import interact


HOST = '127.0.0.1'


def check_states(nodes):
    if len(nodes) < 1:
        raise ValueError('no nodes given')

    states = []
    for node, port in nodes.items():
        result = interact.request(HOST, port, 'get')
        if not result:
            print('Failed to retrieve state of %s' % node)
            return None

        state = result[1:-1].split(',')
        state_set = set(state)
        equal = all(x == state_set for x in states)

        if not equal:
            print('State of node "%s" [%s] does not match with other states: %s' %
                  (node, state_set, str(states)))
            return None
        states.append(state_set)
    return states[0]


class StateOperation(interact.Operation):

    def __init__(self, crash):
        self.idx = 0
        self.crash = max(0.0, min(crash, 1.0))
        self.values = []

    def init(self, host, nodes):
        # we are trying to guess the current last state value
        # this is not exactly necessary but makes running
        # consecutive test runs easier to analyze
        result = interact.request(host, nodes[nodes.keys()[0]], 'get')
        if result:
            match = re.search(r'(\d+)]$', result)
            if match:
                self.idx = int(match.group(1))

    def operation(self, node, idx):
        if random.random() < self.crash:
            return 'crash'

        self.idx += 1
        value = '%s %d' % (node, self.idx)
        self.values.append(value)

        return value


if __name__ == '__main__':
    PARSER = argparse.ArgumentParser(description='start state actor chaos test', formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    PARSER.add_argument('-i', '--iterations', type=int, default=30, help='number of failure/partition iterations')
    PARSER.add_argument('--interval', type=float, default=0.1, help='delay between requests')
    PARSER.add_argument('-l', '--locations', type=int, default=3, help='number of locations')
    PARSER.add_argument('-d', '--delay', type=int, default=10, help='delay between each random partition')
    PARSER.add_argument('-r', '--runners', type=int, default=3, help='number of request runners')
    PARSER.add_argument('--settle', type=int, default=60, help='final timeout for application to settle')
    PARSER.add_argument('--crash', type=float, default=0.05, help='crash probability (in %%)')
    PARSER.add_argument('--restarts', default=0.2, type=float, help='restart probability (in %%)')

    ARGS = PARSER.parse_args()

    # we are making some assumptions in here:
    # every location is named 'location<id>' and its TCP port (8080)
    # is mapped to the host port '10000+<id>'
    NODES = dict(('location%d' % idx, 10000+idx) for idx in xrange(1, ARGS.locations+1))
    OPS = [StateOperation(ARGS.crash / ARGS.runners) for _ in xrange(ARGS.runners)]

    if not interact.requests_with_chaos(OPS, HOST, NODES, ARGS.iterations, ARGS.interval, ARGS.settle, ARGS.delay, ARGS.restarts):
        sys.exit(1)

    states = check_states(NODES)
    if not states:
        sys.exit(1)

    print('States of %d nodes match up as expected' % (len(NODES)))

