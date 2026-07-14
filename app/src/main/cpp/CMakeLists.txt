cmake_minimum_required(VERSION 3.22.1)
project("vpn-engine")

add_library(
        vpn-engine
        SHARED
        packet_parser.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        vpn-engine
        ${log-lib})
