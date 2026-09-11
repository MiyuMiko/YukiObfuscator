#pragma once

int addValues(int leftValue, int rightValue);

class Accumulator {
public:
    explicit Accumulator(int initialValue) : currentValue(initialValue) {}
    ~Accumulator() = default;
    int value() const { return currentValue; }

private:
    int currentValue;
};
