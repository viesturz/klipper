#ifndef KIN_COREXY_H
#define KIN_COREXY_H

#include <stdint.h> // int32_t
#include "itersolve.h"
#include "compiler.h" // __visible

struct stepper_kinematics * __visible corexy_stepper_alloc(char axis);

#endif // KIN_COREXY_H
