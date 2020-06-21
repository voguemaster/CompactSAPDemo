# CompactSAPDemo
<h2>An allocation-free implementation of a 2D SAP broadphase proxy in Java</h2>

This small demo shows production ready code for a sweep and prune algorithm in Java without any object allocations.
Great for many types of games and works relatively fast (although I bet it isn't the most optimized it can be :-).
This showcases many spheres animating together and dyed based on their collision states.

Generally any game entity is described using a broadphase proxy in the SAP data structure. The entity's AABB is basically comprised
of 4 endpoint "structs" (really just encoded 64-bit long numbers): minimum/maximum in X and minimum/maximum in Y.

Potentially overlapping pairs are encoded as long values too by using the broadphase IDs of the entities.

