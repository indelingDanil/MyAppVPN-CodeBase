cmake_minimum_required(VERSION 3.22.1)
project("myappvpn")

add_library(tun_helper SHARED tun_helper.cpp)
target_link_libraries(tun_helper log)
