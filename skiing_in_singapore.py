# -*- coding: utf-8 -*-
from __future__ import unicode_literals, print_function
import re
import itertools


class Node(object):
    def __init__(self, index, x, y, elevation):
        self.i = index
        self.x = x
        self.y = y
        self.elevation = elevation
        self.go_down = []
        """:type: list[Node]"""
        self.go_up = []
        """:type: list[Node]"""
        self.longest_path = 0


class SkiMap(object):
    def __init__(self, width, height):
        self.width = width
        self.height = height
        self.rows = None
        """:type: list[list[int]"""

        self.nodes = []
        """:type: list[Node]"""

        self.summits = set()
        """:type: set[int]"""

    def build_nodes(self):
        print('Build nodes:')
        go_down = self._go_down
        rows = self.rows
        self.nodes = nodes = [None] * self.width * self.height
        prev_row = prev_row_nodes = None
        i = 0

        for y in range(self.height):
            row = rows[y]
            row_nodes = [None] * self.width
            """:type: list[Node]"""

            for x in range(self.width):
                elevation = row[x]
                node = Node(i, x, y, elevation)
                nodes[i] = row_nodes[x] = node

                if x > 0 and row[x-1] != elevation:
                    if row[x-1] < elevation:
                        go_down(node, row_nodes[x-1])
                    else:
                        go_down(row_nodes[x-1], node)

                if prev_row and prev_row[x] != elevation:
                    if prev_row[x] < elevation:
                        go_down(node, prev_row_nodes[x])
                    else:
                        go_down(prev_row_nodes[x], node)

                i += 1
                if i % 10000 == 0: print(".", end='')

            prev_row = row
            prev_row_nodes = row_nodes

    def longest_paths(self, path_len=None):
        """
        :rtype: list[Node]
        """
        nodes = self.nodes
        if path_len is None:
            upper_summit_index = max(self.summits, key=lambda s_i: nodes[s_i].longest_path)
            path_len = nodes[upper_summit_index].longest_path

        return list(filter(lambda node: node.longest_path == path_len, map(lambda i: nodes[i], self.summits)))

    def _go_down(self, upper_node, down_node):
        """
        :param Node upper_node: from
        :param Node down_node: to
        """
        upper_node.go_down.append(down_node)
        down_node.go_up.append(upper_node)

        if down_node.longest_path + 1 > upper_node.longest_path:
            upper_node.longest_path = new_longest_path = down_node.longest_path + 1
            top_nodes = upper_node.go_up
            level = 1
            while top_nodes:
                next_nodes = []
                for node in top_nodes:
                    node.longest_path = max(node.longest_path, new_longest_path + level)
                    next_nodes.extend(node.go_up)
                top_nodes = next_nodes
                level += 1

        if len(down_node.go_down) > 0:
            try:
                self.summits.remove(down_node.i)
            except KeyError:
                pass
        if len(upper_node.go_down) == 1 and not upper_node.go_up:
            self.summits.add(upper_node.i)

    @classmethod
    def load(cls, filename):
        with open(filename, 'r') as f:
            header = f.readline()
            m = re.search('(\d+) (\d+)', header)
            width = int(m.group(1))
            height = int(m.group(2))
            result = cls(width, height)
            result.rows = list([None] * height)
            for r in range(height):
                row = f.readline()
                result.rows[r] = list(map(int, re.findall('\d+', row)))
                assert len(result.rows[r]) == width
        return result


def find_longest_path(skimap):
    """
    Builds directional graph from Ski Map where nodes are map items and
    edges are possible transitions from upper elevation to adjusted lower items.
    Returns tuple of longest path length (aka graph diameter - number of edges from upper area to lowest area)
    and maximum vertical drop of longest paths (i.e. upper node elevation - bottom node elevation)
    :param SkiMap skimap: map
    :rtype: tuple[int]
    """
    skimap.build_nodes()
    print('Total %d nodes' % len(skimap.nodes))
    print('Total %d summits' % len(skimap.summits))
    paths = skimap.longest_paths()
    max_path_len = paths[0].longest_path
    max_vertical_drop = 0
    print('Found %d longest paths with max len %d' % (len(paths), max_path_len))

    for path_upper_node in paths:
        upper_node_elevation = min_elevation = path_upper_node.elevation
        print('Result x: %d, y: %d, elevation: %d' % (path_upper_node.x, path_upper_node.y, upper_node_elevation))
        prev_level_nodes = [path_upper_node]
        for level in range(1, max_path_len+1):
            level_nodes = set(itertools.chain(*map(lambda _n: _n.go_down, prev_level_nodes)))
            prev_level_nodes = []
            for node in level_nodes:
                # Check nodes on critical (longest) path only
                if node.longest_path == max_path_len - level:
                    node_elevation = node.elevation
                    if node_elevation < min_elevation:
                        min_elevation = node_elevation

                    print('Level[%d]: Next down x: %d, y: %d, elevation: %d' % (level, node.x, node.y, node_elevation))
                    prev_level_nodes.append(node)

        vertical_drop = upper_node_elevation - min_elevation
        print('Min elevation (steepest route final elevation): %d (from summit: %d)' % (min_elevation, vertical_drop))
        if vertical_drop > max_vertical_drop:
            max_vertical_drop = vertical_drop

    print()
    for path_len in range(max_path_len - 1, max_path_len - 4, -1):
        other_results = skimap.longest_paths(path_len=path_len)
        print('Other paths with len %d: %d' % (path_len, len(other_results)))

    return max_path_len, max_vertical_drop


if __name__ == '__main__':
    skimap = SkiMap.load('map.txt')
    print('Processing map [%d x %d]...' % (skimap.width, skimap.height))
    longest_path_len, vertical_drop = find_longest_path(skimap)

    print()
    print('email: %d%d@redmart.com?' % (longest_path_len+1, vertical_drop))
