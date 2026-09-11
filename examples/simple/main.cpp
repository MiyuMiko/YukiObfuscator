#include "calculator.hpp"
#include "shared_types.hpp"
#include <array>
#include <iostream>

enum class Mode { FAST, SLOW };
int /* original block comment */ globalCount = 1;

int addValues(int leftValue, int rightValue) {
    int total = leftValue + rightValue;
    return total;
}

int main() {
    int first, second;
    int alpha = 1, beta = 2;
    Accumulator accumulator{alpha};
    LegacyCounter legacy{4};
    count_type inheritedCount = 3;
    std::array<int, 3> values{1, 2, 3};
    int (*operation)(int, int) = addValues;
    first = globalCount;
    second = 2;
    int result = operation(20, 22);
    const char* message = "total stays in strings";
    const char* rawMessage = R"(result stays in raw strings)";
    // total stays in comments
    std::cout << message << rawMessage << ": "
              << result + first + second + alpha + beta + inheritedCount + values[0]
              + accumulator.value() + legacy.amount << '\n';
    return 0;
}
