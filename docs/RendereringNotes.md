# Rendering Notes
- width and depth of fov is configurable
- calculate fov from center back of the cell in facing direction, i.e. party's cell is included in the number of depth cells 
- front walls and side walls are rendered differently and collected separately
- for a mob position and facing direction, mob is at (lateral, depth) (0,0), depth increases in facing direction and decreases in the opposite direction, lateral decreases to the left (of facing direction) and increases to the right
- only consider cells at depth >= 0 for rendering
- in a lat strip, if (depth - 1) is a wal then cell at depth can be ignored for front rendering
- in a depth strip, if lat closer to 0 (if (lat != 0) lat - sign(lat)) is a wall then ignore cell at lat for side wall
 