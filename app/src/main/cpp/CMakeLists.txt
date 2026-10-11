cmake_minimum_required(VERSION 3.22.1)
project("myappvpn")

add_library(tun_helper SHARED tun_helper.cpp)
target_link_libraries(tun_helper log)
# 16 KB page-size alignment (Android 15+ devices / Play requirement)
target_link_options(tun_helper PRIVATE "-Wl,-z,max-page-size=16384")
