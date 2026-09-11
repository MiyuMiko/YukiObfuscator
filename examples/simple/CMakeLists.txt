cmake_minimum_required(VERSION 3.16)
project(SimpleExample LANGUAGES CXX)

add_executable(simple main.cpp calculator.hpp shared_types.hpp)
target_compile_features(simple PRIVATE cxx_std_17)
