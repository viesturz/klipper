#ifndef KIN_CARTESIAN_H
#define KIN_CARTESIAN_H

#include <stdint.h> // int32_t
#include "itersolve.h"
#include "compiler.h" // __visible

struct stepper_kinematics * __visible cartesian_stepper_alloc(char axis);

#endif // kin_cartesian.h
